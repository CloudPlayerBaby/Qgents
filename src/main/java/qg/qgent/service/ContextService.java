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
                .map(m -> new ContextMessage(m.getSequenceNo(), m.getMessageType(),
                        senderType(m), senderId(m), messageText(m.getContent())))
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
            conversation.add(new ContextMessage(trigger.getSequenceNo(), trigger.getMessageType(), senderType(trigger),
                    senderId(trigger), messageText(trigger.getContent())));
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
                .map(m -> new ContextMessage(m.getSequenceNo(), m.getMessageType(),
                        senderType(m), senderId(m), messageText(m.getContent())))
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
     * 从结构化 content 提取可读文本（TEXT 取 text；其余类型返回原始 JSON）。
     */
    private String messageText(String contentJson) {
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
}
