package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.MemoryCreateRequest;
import qg.qgent.dto.MemoryDraftRequest;
import qg.qgent.dto.MemoryResponse;
import qg.qgent.dto.MemorySourceRef;
import qg.qgent.dto.MemoryUpdateRequest;
import qg.qgent.dto.UserSummary;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MemoryMessageSourceMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Memory 业务：手动/ AI 草稿创建、编辑、审核批准/拒绝、归档（契约 §9）。
 * <p>
 * 状态机：DRAFT → PENDING_REVIEW → APPROVED | REJECTED；APPROVED → ARCHIVED；
 * REJECTED 可重新提交。AI 只能生成草稿，不得直接批准或发布。
 */
@Service
public class MemoryService {
    private static final Set<String> SUBMITTABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE = Set.of("DRAFT", "PENDING_REVIEW");
    private static final String AI_SYSTEM_PROMPT = "你是项目知识沉淀助手。根据给定群聊消息和沉淀指令，输出严格 JSON，"
            + "格式为 {\"title\":\"简短标题\",\"content\":\"经确认的项目事实陈述，不得编造\","
            + "\"category\":\"如 ENGINEERING_DECISION/ARCHITECTURE_CONSTRAINT\",\"tags\":[\"标签\"]}。";

    private final MemoryMapper memoryMapper;
    private final MemoryMessageSourceMapper sourceMapper;
    private final MessageMapper messageMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final ChatClient chatClient;
    private final ObjectMapper mapper;

    public MemoryService(MemoryMapper memoryMapper, MemoryMessageSourceMapper sourceMapper,
            MessageMapper messageMapper, ProjectAccessService access, UserMapper userMapper,
            ChatClient.Builder chatClientBuilder, ObjectMapper mapper) {
        this.memoryMapper = memoryMapper;
        this.sourceMapper = sourceMapper;
        this.messageMapper = messageMapper;
        this.access = access;
        this.userMapper = userMapper;
        this.chatClient = chatClientBuilder.build();
        this.mapper = mapper;
    }

    /**
     * 手动创建 Memory 草稿（DRAFT）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param request   创建请求
     * @return Memory 视图
     */
    @Transactional
    public MemoryResponse create(UUID actor, UUID projectId, MemoryCreateRequest request) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = new MemoryEntity();
        memory.setId(UuidV7.next());
        memory.setProjectId(projectId);
        memory.setCreatedBy(actor);
        memory.setTitle(request.getTitle().trim());
        memory.setContent(request.getContent().trim());
        memory.setCategory(request.getCategory().trim());
        memory.setTags(request.getTags());
        memory.setStatus("DRAFT");
        memoryMapper.insert(memory);
        return toResponse(memoryMapper.selectById(memory.getId()));
    }

    /**
     * 项目内查询 Memory：默认仅 APPROVED；非 APPROVED 状态仅创建者或 Admin 可见；支持状态、标签过滤。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param status    状态过滤，可为空（为空即默认 APPROVED）
     * @param tag       标签过滤，可为空
     * @return Memory 列表
     */
    public List<MemoryResponse> list(UUID actor, UUID projectId, String status, String tag) {
        String role = access.requireProjectMember(projectId, actor);
        boolean isAdmin = "PROJECT_ADMIN".equals(role);
        return memoryMapper.listMemories(projectId, actor, isAdmin, blankToNull(status), blankToNull(tag))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 获取 Memory；他人非 APPROVED Memory 视为不可见。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param memoryId  Memory ID
     * @return Memory 视图
     */
    public MemoryResponse get(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!"APPROVED".equals(memory.getStatus()) && !memory.getCreatedBy().equals(actor)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return toResponse(memory);
    }

    /**
     * 编辑草稿或审核中内容；仅创建者或 Project Admin，且状态为 DRAFT/PENDING_REVIEW。
     */
    @Transactional
    public MemoryResponse update(UUID actor, UUID projectId, UUID memoryId, MemoryUpdateRequest request) {
        MemoryEntity memory = requireEditable(actor, projectId, memoryId);
        if (request.getTitle() != null) {
            memory.setTitle(request.getTitle().trim());
        }
        if (request.getContent() != null) {
            memory.setContent(request.getContent().trim());
        }
        if (request.getCategory() != null) {
            memory.setCategory(request.getCategory().trim());
        }
        if (request.getTags() != null) {
            memory.setTags(request.getTags());
        }
        memoryMapper.updateById(memory);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * 根据选中的群聊消息生成 AI 草稿（DRAFT），并记录来源消息（契约 §9 群聊生成草稿）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param request   生成请求（来源消息 + 沉淀指令）
     * @return AI 生成的 Memory 草稿
     */
    @Transactional
    public MemoryResponse createAiDraft(UUID actor, UUID projectId, MemoryDraftRequest request) {
        access.requireProjectMember(projectId, actor);
        List<MemorySourceRef> sources = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        for (MemorySourceRef ref : request.getSourceMessages()) {
            MessageEntity message = messageMapper.selectById(ref.getMessageId());
            if (message == null || !message.getRequirementGroupId().equals(ref.getGroupId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_MESSAGE_NOT_FOUND",
                        "来源消息不存在或不属于该群");
            }
            sources.add(ref);
            transcript.append("- [").append(message.getMessageType()).append("] ")
                    .append(readMessageText(message.getContent())).append('\n');
        }

        String content;
        try {
            content = chatClient.prompt().system(AI_SYSTEM_PROMPT)
                    .user("群聊消息：\n" + transcript + "\n沉淀指令：" + request.getInstruction()).call().content();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_DRAFT_FAILED", "AI 草稿生成失败: " + e.getMessage());
        }
        AiDraft draft = parseAiDraft(content);

        MemoryEntity memory = new MemoryEntity();
        memory.setId(UuidV7.next());
        memory.setProjectId(projectId);
        memory.setCreatedBy(actor);
        memory.setTitle(draft.getTitle());
        memory.setContent(draft.getContent());
        memory.setCategory(draft.getCategory() == null ? "ENGINEERING_DECISION" : draft.getCategory());
        memory.setTags(draft.getTags());
        memory.setStatus("DRAFT");
        memoryMapper.insert(memory);
        for (MemorySourceRef source : sources) {
            sourceMapper.insertSource(memory.getId(), source.getMessageId());
        }
        return toResponse(memoryMapper.selectById(memory.getId()));
    }

    /**
     * 提交审核；仅创建者或 Project Admin，且状态为 DRAFT/REJECTED。
     */
    @Transactional
    public MemoryResponse submitReview(UUID actor, UUID projectId, UUID memoryId) {
        MemoryEntity memory = requireOwned(actor, projectId, memoryId);
        if (!SUBMITTABLE.contains(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("PENDING_REVIEW");
        memoryMapper.updateById(memory);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * 批准并发布 Memory（Project Admin）；仅 PENDING_REVIEW。
     */
    @Transactional
    public MemoryResponse approve(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectAdmin(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!"PENDING_REVIEW".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("APPROVED");
        memory.setReviewerId(actor);
        memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * 拒绝 Memory 并给出原因（Project Admin）；仅 PENDING_REVIEW。
     */
    @Transactional
    public MemoryResponse reject(UUID actor, UUID projectId, UUID memoryId, String reason) {
        access.requireProjectAdmin(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!"PENDING_REVIEW".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("REJECTED");
        memory.setReviewerId(actor);
        memory.setRejectionReason(reason);
        memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * 归档 Memory（Project Admin）；仅 APPROVED。
     */
    @Transactional
    public MemoryResponse archive(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectAdmin(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!"APPROVED".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("ARCHIVED");
        memoryMapper.updateById(memory);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    private MemoryEntity requireMemoryInProject(UUID projectId, UUID memoryId) {
        MemoryEntity memory = memoryMapper.selectById(memoryId);
        if (memory == null || !memory.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return memory;
    }

    private MemoryEntity requireOwned(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!memory.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        return memory;
    }

    private MemoryEntity requireEditable(UUID actor, UUID projectId, UUID memoryId) {
        MemoryEntity memory = requireOwned(actor, projectId, memoryId);
        if (!EDITABLE.contains(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        return memory;
    }

    private ApiException stateConflict(String status) {
        return new ApiException(HttpStatus.CONFLICT, "MEMORY_STATE_CONFLICT", "Memory 当前状态不允许该操作: " + status);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String readMessageText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = mapper.readValue(contentJson, Map.class);
            Object text = map.get("text");
            return text == null ? contentJson : text.toString();
        } catch (Exception e) {
            return contentJson;
        }
    }

    private AiDraft parseAiDraft(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_DRAFT_FAILED", "AI 未返回内容");
        }
        String json = content;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = content.substring(start, end + 1);
        }
        try {
            return mapper.readValue(json, AiDraft.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_DRAFT_FAILED", "AI 返回无法解析");
        }
    }

    /** AI 草稿解析结果（LLM 输出 JSON 的目标结构）。 */
    @Data
    private static class AiDraft {
        private String title;
        private String content;
        private String category;
        private List<String> tags;
    }

    private MemoryResponse toResponse(MemoryEntity memory) {
        List<MemorySourceRef> sources = sourceMapper.selectMessageIds(memory.getId()).stream()
                .map(messageId -> {
                    MessageEntity message = messageMapper.selectById(messageId);
                    MemorySourceRef ref = new MemorySourceRef();
                    ref.setMessageId(messageId);
                    ref.setGroupId(message == null ? null : message.getRequirementGroupId());
                    return ref;
                }).toList();
        return new MemoryResponse(memory.getId().toString(), memory.getProjectId().toString(), memory.getTitle(),
                memory.getContent(), memory.getCategory(), memory.getTags(), memory.getStatus(),
                userSummary(memory.getCreatedBy()), userSummary(memory.getReviewerId()), memory.getRejectionReason(),
                memory.getReviewedAt(), memory.getCreatedAt(), sources);
    }

    private UserSummary userSummary(UUID userId) {
        if (userId == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(userId);
        return user == null ? null
                : new UserSummary(user.getId().toString(), user.getDisplayName(), user.getAvatarUrl());
    }
}
