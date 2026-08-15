package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 群聊上下文组装（点3：聊天上下文管理）。
 * <p>
 * 把需求群的历史消息、需求、关联仓库、已发布 Skill 与已批准 Memory 组装为 Agent 输入上下文，
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
    private final ObjectMapper mapper;

    public ContextService(RequirementGroupMapper groupMapper, MessageMapper messageMapper, SkillMapper skillMapper,
                          MemoryMapper memoryMapper, RequirementGroupRepositoryMapper groupRepoMapper, ProjectAccessService access,
                          ObjectMapper mapper) {
        this.groupMapper = groupMapper;
        this.messageMapper = messageMapper;
        this.skillMapper = skillMapper;
        this.memoryMapper = memoryMapper;
        this.groupRepoMapper = groupRepoMapper;
        this.access = access;
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
        access.requireProjectMember(projectId, actor);
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

        List<ContextSkill> skills = skillMapper.listSkills(projectId, actor, "PUBLISHED", null).stream()
                .map(s -> new ContextSkill(s.getName(), s.getContent())).toList();
        List<ContextMemory> memories = memoryMapper.listMemories(projectId, actor, isAdmin, "APPROVED", null).stream()
                .map(m -> new ContextMemory(m.getTitle(), m.getContent(), m.getCategory())).toList();
        List<String> repositoryIds = groupRepoMapper.selectRepositoryIds(groupId).stream()
                .map(UUID::toString).toList();

        return new GroupContext(group.getId().toString(), projectId.toString(), group.getName(), group.getDescription(),
                repositoryIds, conversation, skills, memories);
    }

    /**
     * 按关键字与标签检索项目上下文（点6：相关性检索，不默认注入全部）。
     * <p>
     * 返回匹配的已发布 Skill、已批准 Memory 与可选群消息，专供 Agent 作为 prompt 输入；
     * 关键字为空时仅按标签/状态过滤，消息检索仅在关键字非空时执行。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param q         关键字，可为空
     * @param tag       标签过滤，可为空
     * @param groupId   可选需求群 ID，限定消息检索范围
     * @param limit     消息条数上限（默认 50，上限 200）
     * @return 检索结果（Skill + Memory + 消息）
     */
    public ContextSearchResponse search(UUID actor, UUID projectId, String q, String tag, UUID groupId,
                                        Integer limit) {
        access.requireProjectMember(projectId, actor);
        boolean isAdmin = "PROJECT_ADMIN".equals(access.requireProjectMember(projectId, actor));
        int messageLimit = Math.min(Math.max(limit == null ? DEFAULT_MESSAGE_LIMIT : limit, 1), MAX_MESSAGE_LIMIT);

        List<ContextSkill> skills = skillMapper.searchByQuery(projectId, actor, tag, q).stream()
                .map(s -> new ContextSkill(s.getName(), s.getContent())).toList();
        List<ContextMemory> memories = memoryMapper.searchByQuery(projectId, actor, isAdmin, tag, q).stream()
                .map(m -> new ContextMemory(m.getTitle(), m.getContent(), m.getCategory())).toList();
        List<ContextMessage> messages = q == null || q.isBlank() ? List.of()
                : messageMapper.searchByQuery(projectId, groupId, q.trim(), messageLimit).stream()
                .map(m -> new ContextMessage(m.getSequenceNo(), m.getMessageType(),
                        senderType(m), senderId(m), messageText(m.getContent())))
                .toList();
        return new ContextSearchResponse(skills, memories, messages);
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
