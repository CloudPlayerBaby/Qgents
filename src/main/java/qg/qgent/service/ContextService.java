package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.SkillEntity;
import qg.qgent.mapper.*;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 群聊上下文组装（点3：聊天上下文管理）。
 * <p>
 * 把需求群的历史消息、需求、关联仓库、已发布 Skill 目录与已批准 Memory 组装为 Agent 输入上下文，
 * 供 Agent 编排系统（后端1）在创建 Task / 运行 Agent 时作为 prompt 输入。
 */
@Service
public class ContextService {
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 200;

    private final RequirementGroupMapper groupMapper;
    private final MessageMapper messageMapper;
    private final SkillMapper skillMapper;
    private final MemoryMapper memoryMapper;
    private final RequirementGroupRepositoryMapper groupRepoMapper;
    private final ProjectAccessService access;
    private final GroupService groupService;
    private final ObjectMapper mapper;

    public ContextService(RequirementGroupMapper groupMapper, MessageMapper messageMapper, SkillMapper skillMapper,
                          MemoryMapper memoryMapper, RequirementGroupRepositoryMapper groupRepoMapper, ProjectAccessService access,
                          GroupService groupService, ObjectMapper mapper) {
        this.groupMapper = groupMapper;
        this.messageMapper = messageMapper;
        this.skillMapper = skillMapper;
        this.memoryMapper = memoryMapper;
        this.groupRepoMapper = groupRepoMapper;
        this.access = access;
        this.groupService = groupService;
        this.mapper = mapper;
    }

    /**
     * 组装需求群的 Agent 输入上下文。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param limit     近期消息条数（默认 50，上限 200）
     * @return 群聊上下文
     */
    public GroupContext buildForGroup(UUID actor, UUID projectId, UUID groupId, Integer limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        int messageLimit = Math.min(Math.max(limit == null ? DEFAULT_MESSAGE_LIMIT : limit, 1), MAX_MESSAGE_LIMIT);
        boolean isAdmin = "PROJECT_ADMIN".equals(access.requireProjectMember(projectId, actor));

        // 近期消息：取最新 N 条后反转成旧→新，便于 Agent 理解对话脉络
        List<MessageEntity> newest = messageMapper.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequirementGroupId, groupId)
                .orderByDesc(MessageEntity::getSequenceNo)
                .last("limit " + messageLimit));
        Collections.reverse(newest);
        List<ContextMessage> conversation = newest.stream()
                .map(this::toContextMessage)
                .toList();

        // 默认提示只提供可激活 Skill 的目录，禁止把未显式选择的正文提前读入模型上下文。
        List<ContextSkill> skills = skillMapper.listPublishedCatalog(projectId, actor);
        List<ContextMemory> memories = memoryMapper.listMemories(projectId, actor, isAdmin, "APPROVED", null).stream()
                .map(m -> new ContextMemory(m.getTitle(), m.getContent(), m.getCategory())).toList();
        List<String> repositoryIds = groupRepoMapper.selectRepositoryIds(groupId).stream()
                .map(UUID::toString).toList();

        return new GroupContext(group.getId().toString(), projectId.toString(), group.getName(), group.getDescription(),
                repositoryIds, conversation, skills, memories);
    }

    /**
     * 创建 Task 时捕获默认上下文，并确保触发消息全文存在于快照中。
     * <p>
     * 触发消息可能早于近期窗口；此处按群内序号补入而非截断，避免 Task 核心来源在重试时丢失。
     */
    public GroupContext buildTaskSnapshot(UUID actor, UUID projectId, UUID groupId, UUID triggerMessageId) {
        GroupContext context = buildForGroup(actor, projectId, groupId, DEFAULT_MESSAGE_LIMIT);
        if (triggerMessageId == null) {
            return context;
        }
        MessageEntity trigger = messageMapper.selectById(triggerMessageId);
        if (trigger == null || !groupId.equals(trigger.getRequirementGroupId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRIGGER_MESSAGE_GROUP_MISMATCH",
                    "触发消息不属于当前需求群");
        }
        List<ContextMessage> conversation = new ArrayList<>(context.getConversation());
        if (conversation.stream().noneMatch(message -> trigger.getSequenceNo().equals(message.getSequence()))) {
            conversation.add(toContextMessage(trigger));
            conversation.sort(java.util.Comparator.comparing(ContextMessage::getSequence));
        }
        return new GroupContext(context.getGroupId(), context.getProjectId(), context.getRequirementTitle(),
                context.getRequirementDescription(), context.getRepositoryIds(), conversation, context.getSkills(),
                context.getMemories());
    }

    /**
     * 在指定需求群内按关键字检索历史聊天记录。Skill 与 Memory 不属于聊天检索范围。
     */
    public List<ContextMessage> searchChatHistory(UUID actor, UUID projectId, UUID groupId, String query,
                                                   Integer limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        if (query == null || query.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT_SEARCH_QUERY_REQUIRED", "聊天检索关键字不能为空");
        }
        int messageLimit = Math.min(Math.max(limit == null ? 10 : limit, 1), 50);
        return messageMapper.searchByQuery(projectId, List.of(groupId), query.trim(), messageLimit).stream()
                .map(this::toContextMessage)
                .toList();
    }

    /**
     * 读取一条当前项目内可见且已发布的 Skill 正文。调用方必须显式选择该 Skill。
     */
    public SkillEntity activateSkill(UUID actor, UUID projectId, UUID skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = skillMapper.findVisiblePublishedById(projectId, actor, skillId);
        if (skill == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_AVAILABLE", "Skill 不存在、未发布或无权访问");
        }
        return skill;
    }

    private String senderType(MessageEntity m) {
        if (m.getAgentId() != null) {
            return "AGENT";
        }
        return m.getAuthorUserId() == null ? "SYSTEM" : "USER";
    }

    private String senderId(MessageEntity m) {
        if (m.getAgentId() != null) {
            return m.getAgentId().toString();
        }
        return m.getAuthorUserId() == null ? null : m.getAuthorUserId().toString();
    }

    /**
     * 把消息实体转换为上下文消息：TEXT 取 content.text；IMAGE/FILE 提取 attachmentId/name/mediaType
     * 供多模态输入链路使用，正文为空串（由渲染层生成附件引用，不再把原始 JSON 退化成 prompt 文本）。
     * 存量无 attachmentId 的 IMAGE/FILE 消息同样渲染为附件占位引用。
     * <p>
     * attachmentId 优先取 content.attachmentId；部分客户端只写 content.url（形如
     * .../attachments/{uuid}/content），此时从 url 反解，保证多模态链路能读到图片/文件字节。
     */
    private ContextMessage toContextMessage(MessageEntity m) {
        Map<?, ?> content = parseContent(m.getContent());
        String type = m.getMessageType();
        String text = "IMAGE".equals(type) || "FILE".equals(type)
                ? ""
                : extractText(content, m.getContent());
        String attachmentId = stringField(content, "attachmentId");
        if (attachmentId == null && ("IMAGE".equals(type) || "FILE".equals(type))) {
            attachmentId = attachmentIdFromUrl(stringField(content, "url"));
        }
        return new ContextMessage(m.getSequenceNo(), type, senderType(m), senderId(m), text,
                attachmentId, stringField(content, "name"),
                stringField(content, "mediaType"));
    }

    /**
     * 从附件 content URL 中反解附件 ID。URL 形如
     * {@code /api/v1/projects/{projectId}/attachments/{attachmentId}/content}
     * 或 {@code http://host:port/api/v1/projects/{projectId}/attachments/{attachmentId}/content}；
     * 无法解析时返回 null。
     */
    private String attachmentIdFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String path = uri.getPath();
            int marker = path.lastIndexOf("/attachments/");
            if (marker < 0) {
                return null;
            }
            String tail = path.substring(marker + "/attachments/".length());
            int slash = tail.indexOf('/');
            String id = slash < 0 ? tail : tail.substring(0, slash);
            return id.isBlank() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析 content JSON 为字段映射；空/非法时返回空映射，不抛异常。
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> parseContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = mapper.readValue(contentJson, Map.class);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 提取 content.text 可读文本；无 text 键时返回原始 JSON（非 IMAGE/FILE 类型的兼容兜底）。
     */
    private String extractText(Map<?, ?> content, String contentJson) {
        Object text = content.get("text");
        return text == null ? contentJson : text.toString();
    }

    /**
     * 取 content 字段的字符串值；缺失返回 null。
     */
    private String stringField(Map<?, ?> content, String key) {
        Object value = content.get(key);
        return value == null ? null : value.toString();
    }
}
