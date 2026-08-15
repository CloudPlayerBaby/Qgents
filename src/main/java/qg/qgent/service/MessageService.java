package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 群消息业务：发送（类型校验、clientMessageId 幂等、sequence 单调递增）与游标分页拉取（契约 §7）。
 */
@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final Set<String> PUBLIC_TYPES = Set.of("TEXT", "CODE", "IMAGE", "FILE", "DIFF", "TASK_STATUS",
            "QUOTE");
    private static final String CURSOR_PREFIX = "cursor_";

    private final MessageMapper messageMapper;
    private final RequirementGroupMapper groupMapper;
    private final GroupAgentMapper groupAgentMapper;
    private final UserMapper userMapper;
    private final AgentMapper agentMapper;
    private final ProjectAccessService access;
    private final TaskTriggerService taskTriggerService;
    private final ObjectMapper mapper;

    public MessageService(MessageMapper messageMapper, RequirementGroupMapper groupMapper,
                          GroupAgentMapper groupAgentMapper, UserMapper userMapper, AgentMapper agentMapper,
                          ProjectAccessService access, TaskTriggerService taskTriggerService, ObjectMapper mapper) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.groupAgentMapper = groupAgentMapper;
        this.userMapper = userMapper;
        this.agentMapper = agentMapper;
        this.access = access;
        this.taskTriggerService = taskTriggerService;
        this.mapper = mapper;
    }

    /**
     * 发送消息（用户路径）。
     * <p>
     * 发送前持有群行锁（FOR UPDATE）以保证 sequence 单调递增；client_message_id 在同一群内唯一，
     * 断线重试或并发撞唯一键时返回原消息（幂等）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param body      发送请求
     * @return 消息视图
     */
    @Transactional
    public MessageResponse send(UUID actor, UUID projectId, UUID groupId, MessageSendRequest body) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = lockGroup(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        MessageResponse response = doSend(groupId, actor, null, body);
        triggerTaskOnAgentMention(actor, projectId, groupId, response.getId() == null ? null : UUID.fromString(response.getId()),
                body.getMentions());
        return response;
    }

    /**
     * 若消息提及了 Agent（@agent），自动从该消息创建 Task（点7：聊天消息到 Agent Task 转换）。
     * <p>
     * 自动触发失败不阻塞消息发送（日志 warn）；幂等由 TaskTriggerService 保证（同消息只建一次）。
     */
    private void triggerTaskOnAgentMention(UUID actor, UUID projectId, UUID groupId, UUID messageId,
                                           List<Mention> mentions) {
        if (mentions == null || mentions.stream().noneMatch(m -> "AGENT".equals(m.getType()))) {
            return;
        }
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null) {
            return;
        }
        try {
            taskTriggerService.triggerFromMention(actor, projectId, groupId, message, mentions);
        } catch (RuntimeException e) {
            log.warn("task auto-trigger skipped, messageId={}: {}", messageId, e.getMessage());
        }
    }

    /**
     * Agent 发送消息（内部方法，供 Agent 编排系统调用，实现用户+Agent 共同参与聊天）。
     * <p>
     * Agent 不是登录用户，不执行项目成员校验；群与 Agent 的归属一致性由编排系统保证。
     * 与用户消息共用 sequence 单调递增与 client_message_id 幂等逻辑，响应 senderType=AGENT。
     *
     * @param groupId 需求群 ID
     * @param agentId Agent ID
     * @param body    发送请求
     * @return 消息视图
     */
    @Transactional
    public MessageResponse sendAsAgent(UUID groupId, UUID agentId, MessageSendRequest body) {
        RequirementGroupEntity group = lockGroup(groupId);
        if (group == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在");
        }
        return doSend(groupId, null, agentId, body);
    }

    private RequirementGroupEntity lockGroup(UUID groupId) {
        return groupMapper.selectOne(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getId, groupId)
                .last("FOR UPDATE"));
    }

    private MessageResponse doSend(UUID groupId, UUID authorUserId, UUID agentId, MessageSendRequest body) {
        String type = normalizeType(body.getType());
        validateContent(type, body.getContent());
        List<Mention> mentions = body.getMentions() == null ? List.of() : body.getMentions();
        String clientMessageId = trimmed(body.getClientMessageId());

        // clientMessageId 幂等：断线重试命中直接返回原消息（优先于其余字段校验）
        if (clientMessageId != null) {
            MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
            if (existing != null) {
                return toResponse(existing);
            }
        }
        if (body.getReplyToId() != null) {
            MessageEntity reply = messageMapper.selectById(body.getReplyToId());
            if (reply == null || !reply.getRequirementGroupId().equals(groupId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLY_MESSAGE_NOT_FOUND",
                        "被回复的消息不存在");
            }
        }

        MessageEntity message = new MessageEntity();
        message.setId(UuidV7.next());
        message.setRequirementGroupId(groupId);
        message.setSequenceNo(messageMapper.nextSequence(groupId));
        message.setAuthorUserId(authorUserId);
        message.setAgentId(agentId);
        message.setClientMessageId(clientMessageId);
        message.setMessageType(type);
        message.setContent(writeJson(body.getContent()));
        message.setMentions(writeJson(mentions));
        message.setReplyToMessageId(body.getReplyToId());
        // 显式使用 UTC：不依赖数据库 DEFAULT CURRENT_TIMESTAMP（其取 MySQL 服务器时区，可能为本地时间）
        message.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发下 clientMessageId 撞唯一键：返回已存在的原消息
            if (clientMessageId != null) {
                MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
                if (existing != null) {
                    return toResponse(existing);
                }
            }
            throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_SEND_CONFLICT", "消息发送冲突，请重试");
        }
        groupMapper.update(null, Wrappers.<RequirementGroupEntity>lambdaUpdate()
                .set(RequirementGroupEntity::getLastMessageAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(RequirementGroupEntity::getId, groupId));
        // Agent 首次回群后自动成为群参与者（群成员 = 真实用户 + Agent 混合）
        if (agentId != null) {
            groupAgentMapper.insertAgent(groupId, agentId);
        }
        return toResponse(messageMapper.selectById(message.getId()));
    }

    /**
     * 游标拉取群消息，新消息在前；limit 默认 30、最大 100。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param cursor    上一页游标，可为空
     * @param limit     每页数量（自动收敛到 1..100）
     * @return 消息分页结果
     */
    public PageSlice<MessageResponse> list(UUID actor, UUID projectId, UUID groupId, String cursor, int limit) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        Long cursorSequence = decodeCursor(cursor);
        int pageSize = Math.min(Math.max(limit, 1), 100);
        List<MessageEntity> rows = messageMapper.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequirementGroupId, groupId)
                .lt(cursorSequence != null, MessageEntity::getSequenceNo, cursorSequence)
                .orderByDesc(MessageEntity::getSequenceNo)
                .last("limit " + (pageSize + 1)));
        boolean hasMore = rows.size() > pageSize;
        List<MessageEntity> pageRows = rows.stream().limit(pageSize).toList();
        // 批量加载发送者显示名，避免逐条消息按 senderId 查询造成 N+1
        Map<UUID, String> userNames = loadUserNames(pageRows);
        Map<UUID, String> agentNames = loadAgentNames(pageRows);
        List<MessageResponse> views = pageRows.stream().map(m -> toResponse(m, userNames, agentNames)).toList();
        String nextCursor = hasMore && !views.isEmpty()
                ? encodeCursor(views.get(views.size() - 1).getSequence())
                : null;
        return new PageSlice<>(views, new PageInfo(nextCursor, hasMore));
    }

    private MessageEntity findByClientMessageId(UUID groupId, String clientMessageId) {
        return messageMapper.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequirementGroupId, groupId)
                .eq(MessageEntity::getClientMessageId, clientMessageId));
    }

    private String normalizeType(String type) {
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!PUBLIC_TYPES.contains(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
        return normalized;
    }

    private void validateContent(String type, Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", "消息内容不能为空");
        }
        switch (type) {
            case "TEXT", "QUOTE" -> requireField(content, "text", "文本消息缺少 text 字段");
            case "CODE" -> {
                requireField(content, "language", "代码消息缺少 language 字段");
                requireField(content, "code", "代码消息缺少 code 字段");
            }
            case "IMAGE", "FILE" -> requireField(content, "url", type + " 消息缺少 url 字段");
            case "DIFF" -> requireField(content, "diffId", "Diff 卡片消息缺少 diffId 字段");
            case "TASK_STATUS" -> {
                requireField(content, "taskId", "任务状态卡片缺少 taskId 字段");
                requireField(content, "status", "任务状态卡片缺少 status 字段");
            }
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID",
                    "不支持的消息类型");
        }
    }

    private void requireField(Map<String, Object> content, String field, String message) {
        Object value = content.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", message);
        }
    }

    private MessageResponse toResponse(MessageEntity m) {
        // 单条路径（发送/幂等返回）逐条解析发送者名称；列表路径使用批量加载版本 toResponse(m, userNames, agentNames)
        String senderName = m.getAgentId() != null
                ? agentName(m.getAgentId())
                : m.getAuthorUserId() != null ? userName(m.getAuthorUserId()) : null;
        return toResponse(m, senderName);
    }

    private MessageResponse toResponse(MessageEntity m, Map<UUID, String> userNames, Map<UUID, String> agentNames) {
        String senderName = m.getAgentId() != null ? agentNames.get(m.getAgentId())
                : m.getAuthorUserId() != null ? userNames.get(m.getAuthorUserId()) : null;
        return toResponse(m, senderName);
    }

    private MessageResponse toResponse(MessageEntity m, String senderName) {
        Map<String, Object> content = readJson(m.getContent(), new TypeReference<Map<String, Object>>() {
        });
        List<Mention> mentions = m.getMentions() == null || m.getMentions().isBlank()
                ? List.of()
                : readJson(m.getMentions(), new TypeReference<List<Mention>>() {
        });
        String senderType;
        String senderId;
        if (m.getAgentId() != null) {
            senderType = "AGENT";
            senderId = m.getAgentId().toString();
        } else if (m.getAuthorUserId() != null) {
            senderType = "USER";
            senderId = m.getAuthorUserId().toString();
        } else {
            senderType = "SYSTEM";
            senderId = null;
        }
        return new MessageResponse(m.getId().toString(), m.getRequirementGroupId().toString(), m.getSequenceNo(),
                m.getMessageType(), content, senderId, senderType, senderName,
                m.getReplyToMessageId() == null ? null : m.getReplyToMessageId().toString(), mentions,
                iso(m.getCreatedAt()));
    }

    /**
     * 时间统一序列化为 UTC 并带 Z 后缀（ISO8601），避免前端按本地时区误解析。
     */
    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    /**
     * 批量加载消息页中出现的用户显示名，返回 userId → displayName。
     */
    private Map<UUID, String> loadUserNames(List<MessageEntity> pageRows) {
        Set<UUID> userIds = pageRows.stream().map(MessageEntity::getAuthorUserId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectList(Wrappers.<UserEntity>lambdaQuery().in(UserEntity::getId, userIds)).stream()
                .collect(Collectors.toMap(UserEntity::getId,
                        u -> u.getDisplayName() == null || u.getDisplayName().isBlank() ? "已注销用户"
                                : u.getDisplayName()));
    }

    /**
     * 批量加载消息页中出现的 Agent 名称，返回 agentId → name。
     */
    private Map<UUID, String> loadAgentNames(List<MessageEntity> pageRows) {
        Set<UUID> agentIds = pageRows.stream().map(MessageEntity::getAgentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        return agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery().in(AgentEntity::getId, agentIds)).stream()
                .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName));
    }

    private String userName(UUID userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getDisplayName() == null || user.getDisplayName().isBlank()) {
            return "已注销用户";
        }
        return user.getDisplayName();
    }

    private String agentName(UUID agentId) {
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent == null ? null : agent.getName();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("消息内容序列化失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("消息内容解析失败", e);
        }
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private String encodeCursor(Long sequence) {
        byte[] raw = Long.toString(sequence).getBytes(StandardCharsets.UTF_8);
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (!cursor.startsWith(CURSOR_PREFIX)) {
                throw new IllegalArgumentException();
            }
            byte[] raw = Base64.getUrlDecoder().decode(cursor.substring(CURSOR_PREFIX.length()));
            return Long.parseLong(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标无效");
        }
    }
}
