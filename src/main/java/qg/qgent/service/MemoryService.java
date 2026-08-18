package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MemoryMessageSourceMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

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
    /**
     * AI 自动沉淀一次性读取的最近消息条数上限（按群内 sequence 倒序取最近 N 条）。
     */
    private static final int RECENT_MESSAGE_LIMIT = 40;
    private static final String DEFAULT_INSTRUCTION = "将最近群聊中值得沉淀的内容沉淀为项目 Memory";
    private static final String AI_SYSTEM_PROMPT = "你是项目知识沉淀助手。根据给定的最近群聊消息，自动甄别其中值得长期复用的"
            + "项目事实、约定或工程决策，输出严格 JSON，格式为 {\"title\":\"简短标题\",\"content\":\"经确认的项目事实陈述，不得编造\","
            + "\"category\":\"如 ENGINEERING_DECISION/ARCHITECTURE_CONSTRAINT\",\"tags\":[\"标签\"]}。";

    private final MemoryMapper memoryMapper;
    private final MemoryMessageSourceMapper sourceMapper;
    private final MessageMapper messageMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final EventService eventService;
    private final int maxApprovedMemoryChars;

    @Autowired
    public MemoryService(MemoryMapper memoryMapper, MemoryMessageSourceMapper sourceMapper,
                         MessageMapper messageMapper, RequirementGroupMapper requirementGroupMapper,
                         ProjectMapper projectMapper, ProjectAccessService access, UserMapper userMapper,
                         ChatClient.Builder chatClientBuilder, ObjectMapper mapper, EventService eventService,
                         @Value("${app.agent.context.max-approved-memory-chars:12000}") int maxApprovedMemoryChars) {
        this.memoryMapper = memoryMapper;
        this.sourceMapper = sourceMapper;
        this.messageMapper = messageMapper;
        this.requirementGroupMapper = requirementGroupMapper;
        this.projectMapper = projectMapper;
        this.access = access;
        this.userMapper = userMapper;
        this.chatClient = chatClientBuilder.build();
        this.mapper = mapper;
        this.eventService = eventService;
        this.maxApprovedMemoryChars = Math.max(0, maxApprovedMemoryChars);
    }

    /**
     * 供不启动 Spring 上下文的单元测试使用，采用生产默认预算。
     */
    public MemoryService(MemoryMapper memoryMapper, MemoryMessageSourceMapper sourceMapper,
                         MessageMapper messageMapper, RequirementGroupMapper requirementGroupMapper,
                         ProjectMapper projectMapper, ProjectAccessService access, UserMapper userMapper,
                         ChatClient.Builder chatClientBuilder, ObjectMapper mapper, EventService eventService) {
        this(memoryMapper, sourceMapper, messageMapper, requirementGroupMapper, projectMapper, access, userMapper,
                chatClientBuilder, mapper, eventService, 12000);
    }

    /**
     * 手动创建 Memory。Project Admin 自建免审批：直接 APPROVED 上架为项目共享知识；
     * 普通成员创建为 DRAFT，经提交审核后由 Admin 批准。AI 沉淀草稿（createAiDraft）
     * 即使 Admin 也保持 DRAFT——AI 可生成草稿但不得直接批准（实体约束）。
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
        if (access.isProjectAdmin(projectId, actor)) {
            // Admin 自建免审批：直接批准上架，无需到交付中心自行批准
            lockProjectMemoryBudget(projectId);
            requireApprovedContextCapacity(projectId, memory);
            memoryMapper.insert(memory);
            memory.setStatus("APPROVED");
            memory.setReviewerId(actor);
            memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
            memoryMapper.updateById(memory);
            eventService.publish(projectId, null, "memory.approved", id(memory.getId()),
                    deliveryPayload(projectId, memory.getId()));
        } else {
            memoryMapper.insert(memory);
        }
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
     * 由当前打开的群自动检索最近聊天，生成 AI 草稿（DRAFT），并记录自动选取的来源消息（契约 §9）。
     * <p>
     * 客户端不再勾选消息：只需给 {@code groupId}，服务端读取该群最近 {@link #RECENT_MESSAGE_LIMIT} 条，
     * 交由 AI 甄别值得沉淀的事实并生成一份草稿，供用户/Admin 审核确认（AI 不得直接批准）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param request   生成请求（群 ID + 可选沉淀指令）
     * @return AI 生成的 Memory 草稿
     */
    @Transactional
    public MemoryResponse createAiDraft(UUID actor, UUID projectId, MemoryDraftRequest request) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requirementGroupMapper.selectById(request.getGroupId());
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_NOT_IN_PROJECT",
                    "需求群不存在或不属于该项目");
        }

        // 自动检索：按群内 sequence 倒序取最近 N 条，再还原为时间正序便于 AI 理解对话脉络
        List<MessageEntity> recent = new ArrayList<>(messageMapper.selectList(
                Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getRequirementGroupId, request.getGroupId())
                        .orderByDesc(MessageEntity::getSequenceNo)
                        .last("LIMIT " + RECENT_MESSAGE_LIMIT)));
        recent.sort(Comparator.comparing(MessageEntity::getSequenceNo,
                Comparator.nullsFirst(Long::compareTo)));
        // 空群不消耗 LLM：群内没有任何消息时直接拒绝，避免空上下文生成无意义草稿浪费 token
        if (recent.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_NO_MESSAGES",
                    "该需求群暂无消息，无需沉淀");
        }

        StringBuilder transcript = new StringBuilder();
        for (MessageEntity message : recent) {
            transcript.append("- [").append(message.getMessageType()).append("] ")
                    .append(readMessageText(message.getContent())).append('\n');
        }
        String instruction = request.getInstruction() == null || request.getInstruction().isBlank()
                ? DEFAULT_INSTRUCTION
                : request.getInstruction().trim();

        String content;
        try {
            content = chatClient.prompt().system(AI_SYSTEM_PROMPT)
                    .user("最近群聊消息：\n" + transcript + "\n沉淀指令：" + instruction).call().content();
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
        for (MessageEntity message : recent) {
            sourceMapper.insertSource(memory.getId(), message.getId());
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
        memory.setSubmittedBy(actor);
        memory.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        eventService.publish(projectId, null, "memory.submit-review", id(memoryId),
                deliveryPayload(projectId, memoryId));
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * 批准并发布 Memory（Project Admin）；仅 PENDING_REVIEW。
     */
    @Transactional
    public MemoryResponse approve(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectAdmin(projectId, actor);
        lockProjectMemoryBudget(projectId);
        MemoryEntity memory = requireMemoryInProjectForUpdate(projectId, memoryId);
        if (!"PENDING_REVIEW".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        requireApprovedContextCapacity(projectId, memory);
        memory.setStatus("APPROVED");
        memory.setReviewerId(actor);
        memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        eventService.publish(projectId, null, "memory.approved", id(memoryId),
                deliveryPayload(projectId, memoryId));
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
        eventService.publish(projectId, null, "memory.rejected", id(memoryId),
                deliveryPayload(projectId, memoryId));
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * 归档 Memory（Project Admin）；仅 APPROVED。
     */
    @Transactional
    public MemoryResponse archive(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectAdmin(projectId, actor);
        lockProjectMemoryBudget(projectId);
        MemoryEntity memory = requireMemoryInProjectForUpdate(projectId, memoryId);
        if (!"APPROVED".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("ARCHIVED");
        memoryMapper.updateById(memory);
        eventService.publish(projectId, null, "memory.archived", id(memoryId),
                deliveryPayload(projectId, memoryId));
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /**
     * Memory 交付事件 payload（契约 v1.8.0 §20 DeliveryEventPayload 基座）。
     */
    private Map<String, Object> deliveryPayload(UUID projectId, UUID memoryId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", id(projectId));
        payload.put("resourceType", "MEMORY");
        payload.put("resourceId", id(memoryId));
        payload.put("eventVersion", 1);
        payload.put("updatedAt", LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString());
        return payload;
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private MemoryEntity requireMemoryInProject(UUID projectId, UUID memoryId) {
        MemoryEntity memory = memoryMapper.selectById(memoryId);
        if (memory == null || !memory.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return memory;
    }

    /**
     * 在项目预算锁已持有时读取并锁定待迁移的 Memory，避免并发重复审批同一条记录。
     */
    private MemoryEntity requireMemoryInProjectForUpdate(UUID projectId, UUID memoryId) {
        MemoryEntity memory = memoryMapper.selectByIdForUpdate(memoryId);
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

    /**
     * 确保批准后的全部项目 Memory 仍可完整注入 Agent 上下文。
     */
    private void requireApprovedContextCapacity(UUID projectId, MemoryEntity candidate) {
        long candidateChars = (long) candidate.getTitle().length() + candidate.getContent().length();
        long usedChars = memoryMapper.selectApprovedForUpdate(projectId).stream()
                .mapToLong(memory -> (long) memory.getTitle().length() + memory.getContent().length())
                .sum();
        if (usedChars > (long) maxApprovedMemoryChars - candidateChars) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_APPROVED_CONTEXT_LIMIT_EXCEEDED",
                    "已批准 Memory 超出 Agent 上下文字符预算，请归档或精简既有 Memory 后重试");
        }
    }

    /**
     * 预算相关的 APPROVED 状态迁移均先串行化到项目行；随后使用 Memory 锁定读计算总量。
     */
    private void lockProjectMemoryBudget(UUID projectId) {
        projectMapper.selectByIdForUpdate(projectId);
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

    /**
     * AI 草稿解析结果（LLM 输出 JSON 的目标结构）。
     */
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
        String source = sources.isEmpty() ? "MANUAL" : "MESSAGE";
        return new MemoryResponse(memory.getId().toString(), memory.getProjectId().toString(), memory.getTitle(),
                memory.getContent(), memory.getCategory(), memory.getTags(), memory.getStatus(),
                userSummary(memory.getCreatedBy()), userSummary(memory.getReviewerId()), memory.getRejectionReason(),
                memory.getReviewedAt(), memory.getCreatedAt(), source, sources);
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
