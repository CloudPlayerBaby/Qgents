package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.ProjectEntity;
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
    private final ProjectMapper projectMapper;
    private final ProjectAccessService access;
    private final GroupService groupService;
    private final TaskTriggerService taskTriggerService;
    private final ObjectMapper mapper;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final AttachmentService attachmentService;
    private final ApplicationEventPublisher eventPublisher;

    public MessageService(MessageMapper messageMapper, RequirementGroupMapper groupMapper,
                          GroupAgentMapper groupAgentMapper, UserMapper userMapper, AgentMapper agentMapper,
                          ProjectMapper projectMapper, ProjectAccessService access, GroupService groupService,
                          TaskTriggerService taskTriggerService, ObjectMapper mapper,
                          EventService eventService, NotificationService notificationService,
                          AttachmentService attachmentService, ApplicationEventPublisher eventPublisher) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.groupAgentMapper = groupAgentMapper;
        this.userMapper = userMapper;
        this.agentMapper = agentMapper;
        this.projectMapper = projectMapper;
        this.access = access;
        this.groupService = groupService;
        this.taskTriggerService = taskTriggerService;
        this.mapper = mapper;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.attachmentService = attachmentService;
        this.eventPublisher = eventPublisher;
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
        // 群成员可见性（契约 2026-08-17 严格收紧）：主群=项目成员，需求群=群成员
        groupService.requireGroupMember(projectId, groupId, actor);
        RequirementGroupEntity group = lockGroup(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        MessageResponse response = doSend(projectId, groupId, actor, null, body);
        enrichAttachmentPreview(response, projectId);
        // @Agent 建任务 / @ 用户通知从发送事务移出：消息落库提交后由 MessageSentListener 异步执行，
        // 缩短群行锁持有时间，消息发出与卡片回群不再等待建任务/写通知。
        if (body.getMentions() != null && !body.getMentions().isEmpty()) {
            eventPublisher.publishEvent(new MessageSentEvent(actor, projectId, groupId,
                    response.getId() == null ? null : UUID.fromString(response.getId()), body.getMentions()));
        }
        return response;
    }

    /**
     * 对被 @ 的用户生成站内通知（MESSAGE_MENTION）：发送者、群名与消息文本摘要进通知内容；
     * 排除发送者本人；@Agent 不在此处理（走任务触发）。通知失败不影响消息发送（日志兜底）。
     * <p>
     * 由 {@link MessageSentListener} 在消息事务提交后异步调用，不再占用发送事务与群行锁。
     *
     * @param event      消息发送事件（含 actor/projectId/groupId/messageId/mentions）
     * @param groupName  来源群名（已查好，避免再查一次）
     * @param textPreview 消息文本摘要（由监听器从已落库消息提取；为空则通知不含摘要）
     */
    public void notifyMentionedUsersAfterCommit(MessageSentEvent event, String groupName, String textPreview) {
        List<Mention> mentions = event.mentions();
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        try {
            List<UUID> users = mentions.stream()
                    .filter(m -> "USER".equals(m.getType()))
                    .filter(m -> m.getId() != null && !m.getId().equals(event.actor()))
                    .map(Mention::getId)
                    .distinct()
                    .toList();
            if (users.isEmpty()) {
                return;
            }
            String senderName = senderDisplayName(event.actor());
            String title = "有人在群聊中提到了你";
            String description = senderName + " 在群「" + groupName + "」中提到了你"
                    + (textPreview == null || textPreview.isBlank() ? "" : "：" + textPreview);
            for (UUID userId : users) {
                notificationService.notify(userId, event.projectId(), event.groupId(), "MESSAGE_MENTION",
                        title, description, event.messageId() == null ? null : event.messageId().toString());
            }
        } catch (RuntimeException e) {
            log.warn("mention notification skipped, projectId={}, groupId={}, actor={}: {}",
                    event.projectId(), event.groupId(), event.actor(), e.getMessage());
        }
    }

    /** 发送者显示名（用户）；查询失败回退「成员」。 */
    private String senderDisplayName(UUID actor) {
        try {
            UserEntity sender = userMapper.selectById(actor);
            return sender == null || sender.getDisplayName() == null || sender.getDisplayName().isBlank()
                    ? "成员" : sender.getDisplayName();
        } catch (RuntimeException e) {
            return "成员";
        }
    }

    /** 从消息 content 提取展示文本（TEXT 取 text；QUOTE 取 quotedText；其余返回空）。 */
    private String messageText(Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        Object text = content.get("text");
        if (text instanceof String s && !s.isBlank()) {
            return truncate(s);
        }
        Object quoted = content.get("quotedText");
        if (quoted instanceof String s && !s.isBlank()) {
            return truncate(s);
        }
        return "";
    }

    /**
     * 提取已落库消息的文本摘要（最长 60 字符），供 {@link MessageSentListener} 在事务提交后生成 @ 通知内容。
     * <p>
     * 解析失败返回空串而非抛异常：通知失败不阻塞、摘要缺失只影响通知文案。
     *
     * @param message 已落库消息
     * @return 文本摘要（可能为空串）
     */
    public String extractTextPreview(MessageEntity message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return "";
        }
        try {
            Map<String, Object> content = readJson(message.getContent(),
                    new TypeReference<Map<String, Object>>() {
                    });
            return messageText(content);
        } catch (RuntimeException e) {
            log.warn("message text preview extract failed, messageId={}: {}", message == null ? null : message.getId(),
                    e.getMessage());
            return "";
        }
    }

    private String truncate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }

    /**
     * 回显 IMAGE/FILE 消息时回填附件预览字段（增量契约 §7：previewable / previewType）。
     * <p>
     * 不回填带 token 的 previewUrl：消息内容里的 URL 无法随 token 过期续期，前端拿到过期地址后
     * 没有回退，会再次出现「无法预览」。previewable/previewType 无时效性，前端据此提示「点击预览」
     * 并调 preview-url 接口重新签发。附件缺失/未就绪时保持原样回显，不阻断消息列表。
     */
    private void enrichAttachmentPreview(MessageResponse response, UUID projectId) {
        if (response == null || projectId == null) {
            return;
        }
        String type = response.getType();
        if (!"IMAGE".equals(type) && !"FILE".equals(type)) {
            return;
        }
        Map<String, Object> content = response.getContent();
        if (content == null || content.isEmpty()) {
            return;
        }
        String attachmentId = stringField(content, "attachmentId");
        if (attachmentId == null) {
            attachmentId = attachmentIdFromUrl(stringField(content, "url"));
        }
        if (attachmentId == null) {
            return;
        }
        AttachmentService.MessageAttachmentPreview preview = attachmentService.messagePreview(projectId, attachmentId);
        if (preview == null) {
            return;
        }
        content.put("previewable", preview.isPreviewable());
        content.put("previewType", preview.getPreviewType());
    }

    private String stringField(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * 从附件 content URL 中反解附件 ID。URL 形如
     * {@code /api/v1/projects/{projectId}/attachments/{attachmentId}/content}
     * 或 {@code http://host:port/api/v1/projects/{projectId}/attachments/{attachmentId}/content}。
     */
    private String attachmentIdFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int idx = url.indexOf("/attachments/");
        if (idx < 0) {
            return null;
        }
        String rest = url.substring(idx + "/attachments/".length());
        int slash = rest.indexOf('/');
        int q = rest.indexOf('?');
        int end = slash >= 0 ? slash : (q >= 0 ? q : rest.length());
        String id = rest.substring(0, end);
        return id.isBlank() ? null : id;
    }

    /**
     * Agent 发送消息（内部方法，供 Agent 编排系统调用，实现用户+Agent 共同参与聊天）。
     * <p>
     * Agent 不是登录用户，不执行项目成员校验；服务端校验其 Team 归属与启用状态。
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
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在");
        }
        ProjectEntity project = projectMapper.selectById(group.getProjectId());
        if (project == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        if (!project.getTeamId().equals(agent.getTeamId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_IN_PROJECT_TEAM",
                    "Agent 不属于当前项目的 Team");
        }
        if (!"ACTIVE".equals(agent.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_ACTIVE", "Agent 未启用");
        }
        MessageResponse response = doSend(group.getProjectId(), groupId, null, agentId, body);
        enrichAttachmentPreview(response, group.getProjectId());
        return response;
    }

    /**
     * 自动化卡片发送（内部方法，供编排系统在团队无可用编排助手 Agent 时兜底回群）。
     * <p>
     * 自动化发送者没有用户或 Agent 身份，响应中的 {@code senderType=SYSTEM}；但消息类型必须保留
     * {@code DIFF}/{@code TASK_STATUS} 的业务语义，前端才能渲染 Diff 卡、并把引用 Diff 识别为续作。
     * 此方法不经过用户发送路径，客户端无法伪造无发送者的自动化消息。
     *
     * @param groupId 需求群 ID
     * @param body    仅允许 DIFF 或 TASK_STATUS 的自动化卡片
     * @return 消息视图
     */
    @Transactional
    public MessageResponse sendAsSystem(UUID groupId, MessageSendRequest body) {
        RequirementGroupEntity group = lockGroup(groupId);
        if (group == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在");
        }
        return doSend(group.getProjectId(), groupId, null, null, body, normalizeSystemCardType(body.getType()));
    }

    /**
     * 创建或更新 Task 状态卡。卡片是 Task 在需求群中的唯一自动化状态消息，更新时不改变消息
     * ID、sequence 和创建时间；调用方只提供本次状态增量，已有 Plan/Steps 会被保留。
     *
     * @param groupId 需求群 ID
     * @param agentId 可选的编排 Agent ID；为空时使用 SYSTEM 身份
     * @param body    TASK_STATUS 卡片内容，必须包含 taskId
     * @return 创建或更新后的消息
     */
    @Transactional
    public MessageResponse upsertTaskStatusCard(UUID groupId, UUID agentId, MessageSendRequest body) {
        return upsertAutomationCard(groupId, agentId, body, "TASK_STATUS");
    }

    /**
     * 创建或更新 Task 的唯一 Diff 卡。Diff 卡始终保留 DIFF 类型和顶层 content.diffId，
     * 以便用户引用原消息时继续进入 Diff 续作流程。
     *
     * @param groupId 需求群 ID
     * @param agentId 可选的编排 Agent ID；为空时使用 SYSTEM 身份
     * @param body    DIFF 卡片内容，必须包含 taskId 和 diffId
     * @return 创建或更新后的消息
     */
    @Transactional
    public MessageResponse upsertDiffCard(UUID groupId, UUID agentId, MessageSendRequest body) {
        return upsertAutomationCard(groupId, agentId, body, "DIFF");
    }

    private MessageResponse upsertAutomationCard(UUID groupId, UUID agentId, MessageSendRequest body,
                                                 String expectedType) {
        RequirementGroupEntity group = lockGroup(groupId);
        if (group == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在");
        }
        if (body == null || !expectedType.equalsIgnoreCase(body.getType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_MESSAGE_TYPE_INVALID",
                    "自动化卡片类型不匹配");
        }
        if (agentId != null) {
            validateAgentForProject(group, agentId);
        }
        Map<String, Object> incoming = body.getContent() == null ? Map.of() : body.getContent();
        requireField(incoming, "taskId", "自动化卡片缺少 taskId 字段");
        String taskId = String.valueOf(incoming.get("taskId"));
        String clientMessageId = ("TASK_STATUS".equals(expectedType) ? "task-card-" : "diff-card-") + taskId;
        MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
        if (existing != null) {
            if (!expectedType.equals(existing.getMessageType())) {
                throw new ApiException(HttpStatus.CONFLICT, "CARD_MESSAGE_TYPE_CONFLICT",
                        "任务卡片消息类型与既有消息不一致");
            }
            Map<String, Object> merged = new LinkedHashMap<>(readJson(existing.getContent(),
                    new TypeReference<Map<String, Object>>() {
                    }));
            Object incomingPlan = incoming.get("plan");
            Object existingPlan = merged.get("plan");
            if (incomingPlan instanceof Map<?, ?> incomingPlanMap && existingPlan instanceof Map<?, ?> existingPlanMap) {
                Map<String, Object> plan = new LinkedHashMap<>();
                existingPlanMap.forEach((key, value) -> plan.put(String.valueOf(key), value));
                incomingPlanMap.forEach((key, value) -> {
                    // 后续状态更新通常不重复携带 Planner 摘要，不能用 null 抹掉已生成的 Plan。
                    if (!"summary".equals(String.valueOf(key)) || value != null) {
                        plan.put(String.valueOf(key), value);
                    }
                });
                merged.put("plan", plan);
            }
            incoming.forEach((key, value) -> {
                if (!"plan".equals(key)) merged.put(key, value);
            });
            validateContent(expectedType, merged);
            existing.setContent(writeJson(merged));
            messageMapper.updateById(existing);
            if (agentId != null && groupAgentMapper.insertAgent(groupId, agentId) > 0) {
                eventService.publish(group.getProjectId(), groupId, "group.member.updated", id(groupId),
                        Map.of("projectId", id(group.getProjectId()), "groupId", id(groupId)));
            }
            eventService.publish(group.getProjectId(), groupId, "message.updated", id(existing.getId()),
                    Map.of("projectId", id(group.getProjectId()), "groupId", id(groupId),
                            "messageId", id(existing.getId()), "sequence", existing.getSequenceNo()));
            return toResponse(existing);
        }

        MessageSendRequest normalized = new MessageSendRequest();
        normalized.setType(expectedType);
        normalized.setContent(incoming);
        normalized.setClientMessageId(clientMessageId);
        normalized.setMentions(List.of());
        return doSend(group.getProjectId(), groupId, null, agentId, normalized, expectedType);
    }

    private void validateAgentForProject(RequirementGroupEntity group, UUID agentId) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在");
        }
        ProjectEntity project = projectMapper.selectById(group.getProjectId());
        if (project == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        if (!project.getTeamId().equals(agent.getTeamId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_IN_PROJECT_TEAM",
                    "Agent 不属于当前项目的 Team");
        }
        if (!"ACTIVE".equals(agent.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_ACTIVE", "Agent 未启用");
        }
    }

    private RequirementGroupEntity lockGroup(UUID groupId) {
        return groupMapper.selectOne(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getId, groupId)
                .last("FOR UPDATE"));
    }

    private MessageResponse doSend(UUID projectId, UUID groupId, UUID authorUserId, UUID agentId,
                                   MessageSendRequest body) {
        return doSend(projectId, groupId, authorUserId, agentId, body, normalizeType(body.getType()));
    }

    private MessageResponse doSend(UUID projectId, UUID groupId, UUID authorUserId, UUID agentId,
                                   MessageSendRequest body, String type) {
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
        message.setContent(writeJson(storedContent(body, type)));
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
        // Agent 首次回群后自动成为群参与者（群成员 = 真实用户 + Agent 混合），并推送成员变化事件
        if (agentId != null) {
            if (groupAgentMapper.insertAgent(groupId, agentId) > 0) {
                eventService.publish(projectId, groupId, "group.member.updated", id(groupId),
                        Map.of("projectId", id(projectId), "groupId", id(groupId)));
            }
        }
        // 项目级 SSE：新消息信号（REST 存真相，前端收到后刷新消息列表）
        eventService.publish(projectId, groupId, "message.created", id(message.getId()),
                Map.of("projectId", id(projectId), "groupId", id(groupId), "messageId", id(message.getId()),
                        "sequence", message.getSequenceNo()));
        return toResponse(messageMapper.selectById(message.getId()));
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    /**
     * 落库前构造 content：QUOTE 消息把请求顶层 replyText（契约 §1.4）并入 content，
     * 使 replyText 随消息持久化并在 GET/发送响应中原样回显；其余类型原样存储。
     */
    private Map<String, Object> storedContent(MessageSendRequest body, String type) {
        Map<String, Object> content = body.getContent() == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(body.getContent());
        if ("QUOTE".equals(type) && body.getReplyText() != null && !body.getReplyText().isBlank()) {
            content.put("replyText", body.getReplyText());
        }
        return content;
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
        // 群成员可见性（契约 2026-08-17 严格收紧）：主群=项目成员，需求群=群成员
        groupService.requireGroupMember(projectId, groupId, actor);
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
        views.forEach(v -> enrichAttachmentPreview(v, projectId));
        String nextCursor = hasMore && !views.isEmpty()
                ? encodeCursor(views.get(views.size() - 1).getSequence())
                : null;
        return new PageSlice<>(views, new PageInfo(nextCursor, hasMore));
    }

    /**
     * 拉取指定序号之后的新消息，供实时连接恢复时补齐聊天窗口。
     * 返回结果严格按 sequence 升序；服务端不接受客户端伪造的消息 ID 作为游标。
     */
    public PageSlice<MessageResponse> listAfterSequence(UUID actor, UUID projectId, UUID groupId,
                                                         long afterSequence, int limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        if (afterSequence < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_CURSOR", "afterSequence 必须为非负数");
        }
        int pageSize = Math.min(Math.max(limit, 1), 100);
        List<MessageEntity> rows = messageMapper.selectAfterSequence(groupId, afterSequence, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<MessageEntity> pageRows = rows.stream().limit(pageSize).toList();
        Map<UUID, String> userNames = loadUserNames(pageRows);
        Map<UUID, String> agentNames = loadAgentNames(pageRows);
        List<MessageResponse> views = pageRows.stream().map(m -> toResponse(m, userNames, agentNames)).toList();
        views.forEach(v -> enrichAttachmentPreview(v, projectId));
        String nextCursor = hasMore && !views.isEmpty()
                ? String.valueOf(views.get(views.size() - 1).getSequence()) : null;
        return new PageSlice<>(views, new PageInfo(nextCursor, hasMore));
    }

    /**
     * 按消息 ID 拉取单条群消息（通知跳转精确定位用，契约「群聊@提及-后端接口补充」§六）。
     * <p>
     * 场景：通知点击跳转后，目标消息较旧不在前端已加载的分页窗口内时，前端调用本接口拉取
     * 该条消息合并进本地列表再滚动高亮。权限与群成员可见性校验同列表接口。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param messageId 目标消息 ID
     * @return 消息视图（含发送者名称）
     */
    public MessageResponse getMessage(UUID actor, UUID projectId, UUID groupId, UUID messageId) {
        // 群成员可见性（契约 2026-08-17 严格收紧）：主群=项目成员，需求群=群成员
        groupService.requireGroupMember(projectId, groupId, actor);
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null || !groupId.equals(message.getRequirementGroupId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在或不属于该群");
        }
        MessageResponse response = toResponse(message);
        enrichAttachmentPreview(response, projectId);
        return response;
    }

    private MessageEntity findByClientMessageId(UUID groupId, String clientMessageId) {
        return messageMapper.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequirementGroupId, groupId)
                .eq(MessageEntity::getClientMessageId, clientMessageId));
    }

    private String normalizeType(String type) {
        if (type == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!PUBLIC_TYPES.contains(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
        return normalized;
    }

    /**
     * 无发送者的内部消息只允许两类受控卡片。DIFF 必须保留类型，不能降级为 SYSTEM 文本，
     * 否则引用续作无法从 replyToId 稳定地定位源 Workspace。
     */
    private String normalizeSystemCardType(String type) {
        String normalized = normalizeType(type);
        if (!"DIFF".equals(normalized) && !"TASK_STATUS".equals(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_MESSAGE_TYPE_INVALID",
                    "自动化消息仅支持 DIFF 或 TASK_STATUS 卡片");
        }
        return normalized;
    }

    private void validateContent(String type, Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", "消息内容不能为空");
        }
        switch (type) {
            case "TEXT" -> requireField(content, "text", "文本消息缺少 text 字段");
            // QUOTE 引用消息：content 为引用摘要（quotedMessageId/quotedText/quotedSenderName），无 text 字段
            case "QUOTE" -> {
                requireField(content, "quotedMessageId", "引用消息缺少 quotedMessageId 字段");
                requireField(content, "quotedText", "引用消息缺少 quotedText 字段");
            }
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
        // QUOTE 消息：从 content 提取 replyText 顶层回显（契约 §1.4，与发送请求体同构）
        String replyText = "QUOTE".equals(m.getMessageType())
                && content.get("replyText") instanceof String text && !text.isBlank()
                ? text : null;
        return new MessageResponse(m.getId().toString(), m.getRequirementGroupId().toString(), m.getSequenceNo(),
                m.getMessageType(), content, senderId, senderType, senderName,
                m.getReplyToMessageId() == null ? null : m.getReplyToMessageId().toString(), replyText, mentions,
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
