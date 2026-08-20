package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.config.MdcTaskDecorator;
import qg.qgent.config.PerformanceMetrics;
import qg.qgent.entity.EventEntity;
import qg.qgent.entity.NotificationEventEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TeamEventEntity;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.GroupMemberMapper;
import qg.qgent.mapper.NotificationEventMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TeamEventMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.UserMapper;
import qg.qgent.service.event.DeliveryStartedDomainEvent;
import qg.qgent.service.event.DryRunConflictCandidateDomainEvent;
import qg.qgent.service.event.MrFirstPreflightRequestedDomainEvent;
import qg.qgent.service.event.PreflightCqApprovedDomainEvent;
import qg.qgent.websocket.RealtimeFrame;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 项目级实时事件（SSE 数据源）服务。
 * 写接口在状态变更时调用 {@link #publish} 持久化事件，客户端通过项目事件流订阅；
 * 事件至少保留 24 小时，支持 Last-Event-ID 断线续传，游标过期返回 409 EVENT_CURSOR_EXPIRED。
 * publish 的序号分配并发安全：事务内先对 projects 行加排他锁（FOR UPDATE）串行化同项目
 * 事件写入，再以锁定读（{@code EventMapper#nextSequence}）取当前已提交的最大序号 + 1，
 * 避免并发「MAX+1」撞 {@code uk_event_seq(project_id, sequence_no)} 唯一键 500；
 * stream 用独立轮询线程拉取增量事件，通过 SseEmitter 推送，
 * 客户端断开时以 send 失败退出线程并自动清理。
 */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /**
     * SSE 连接保活心跳间隔（秒），契约约定每 15 秒一次。
     */
    private static final long HEARTBEAT_SECONDS = 15;
    /**
     * 增量事件轮询间隔（毫秒）。
     */
    private static final long POLL_INTERVAL_MS = 2000;
    /**
     * 单次轮询批量拉取上限。
     */
    private static final int BATCH_SIZE = 100;
    /**
     * 事件保留时长（小时），过期事件不再可续传。
     */
    private static final int RETENTION_HOURS = 24;
    /**
     * 单连接空闲超时（毫秒）：心跳会保活，超时后线程退出、客户端按 Last-Event-ID 重连。
     */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    /**
     * 最大并发 SSE 连接数（项目级 + 通知级 + 团队级合计）：每个连接占用一个泵线程（固定 8 线程池
     * 排队）。超过上限时拒绝建立新连接（返回 429），防止连接堆积耗尽泵线程与数据库连接。
     */
    private static final int MAX_SSE_CONNECTIONS = 128;
    /** 同一项目事件写入发生死锁时的最大新事务重试次数。 */
    private static final int EVENT_DEADLOCK_RETRIES = 3;
    /** 死锁重试的初始退避时间（毫秒）。 */
    private static final long EVENT_DEADLOCK_BACKOFF_MS = 50L;

    private final EventMapper eventMapper;
    private final ProjectAccessService projectAccess;
    private final NotificationEventMapper notificationEventMapper;
    private final TeamEventMapper teamEventMapper;
    private final ExecutorService executor;
    private final ApplicationEventPublisher publisher;
    private final RealtimeHub realtimeHub;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final RequirementGroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final TransactionTemplate eventTransaction;
    private final PerformanceMetrics metrics;
    private final AtomicInteger activeSseConnections = new AtomicInteger();

    @Autowired
    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper,
                        ApplicationEventPublisher publisher, RealtimeHub realtimeHub,
                        ProjectMemberMapper projectMemberMapper, TeamMemberMapper teamMemberMapper,
                        RequirementGroupMapper groupMapper, GroupMemberMapper groupMemberMapper,
                        ProjectMapper projectMapper, UserMapper userMapper, TeamMapper teamMapper,
                        PlatformTransactionManager transactionManager, PerformanceMetrics metrics) {
        this.eventMapper = eventMapper;
        this.projectAccess = projectAccess;
        this.notificationEventMapper = notificationEventMapper;
        this.teamEventMapper = teamEventMapper;
        this.publisher = publisher;
        this.realtimeHub = realtimeHub;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.groupMapper = groupMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.eventTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        // SSE 泵线程执行期间保留提交线程的 MDC（requestId），便于按请求串联日志
        MdcTaskDecorator mdcDecorator = new MdcTaskDecorator();
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(mdcDecorator.decorate(r), "sse-event-pump-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        // 每连接占用一个轮询线程；规模扩大后应改为共享调度器或推模式
        this.executor = Executors.newFixedThreadPool(8, factory);
        if (metrics != null) {
            Gauge.builder("qgents.sse.connections", activeSseConnections, AtomicInteger::get)
                    .description("当前活跃的项目 SSE 连接数")
                    .register(metrics.registry());
        }
    }

    /**
     * 保留测试与既有直接构造调用；生产 Spring Bean 始终使用包含用户、团队锁 Mapper 的构造器。
     */
    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper,
                        ApplicationEventPublisher publisher, RealtimeHub realtimeHub,
                        ProjectMemberMapper projectMemberMapper, TeamMemberMapper teamMemberMapper,
                        RequirementGroupMapper groupMapper, GroupMemberMapper groupMemberMapper,
                        ProjectMapper projectMapper) {
        this(eventMapper, projectAccess, notificationEventMapper, teamEventMapper, publisher, realtimeHub,
                projectMemberMapper, teamMemberMapper, groupMapper, groupMemberMapper, projectMapper,
                null, null, null, null);
    }

    /** 保留测试与既有直接构造调用的完整构造器，不启用生产事务重试。 */
    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper,
                        ApplicationEventPublisher publisher, RealtimeHub realtimeHub,
                        ProjectMemberMapper projectMemberMapper, TeamMemberMapper teamMemberMapper,
                        RequirementGroupMapper groupMapper, GroupMemberMapper groupMemberMapper,
                        ProjectMapper projectMapper, UserMapper userMapper, TeamMapper teamMapper) {
        this(eventMapper, projectAccess, notificationEventMapper, teamEventMapper, publisher, realtimeHub,
                projectMemberMapper, teamMemberMapper, groupMapper, groupMemberMapper, projectMapper,
                userMapper, teamMapper, null, null);
    }

    /**
     * 发布一条项目级事件并持久化。
     * 事件以项目内单调递增的 sequenceNo 排序，作为 SSE 游标；payload 必须为已脱敏内容，
     * 禁止包含 Token、密码、私钥等敏感信息。
     * <p>
     * 事件保留清理由独立的定时任务处理，不能放在调用方的业务事务中。否则清理 SQL 的死锁会
     * 回滚 TaskRun、TaskStep 等关键业务写入，造成任务状态与运行记录不一致。
     *
     * @param projectId  所属项目ID（调用方应已校验项目成员身份）
     * @param groupId    可选关联需求群ID，可为 null
     * @param eventType  事件类型，如 task-run.updated / diff.created / merge-request.updated
     * @param resourceId 关联资源ID字符串，如 taskRunId，可为 null
     * @param payload    脱敏事件载荷 JSON
     */
    public void publish(UUID projectId, UUID groupId, String eventType, String resourceId,
                        Map<String, Object> payload) {
        // 已处于业务事务时保持原子性，由调用方事务统一回滚；编排器等非事务调用则由本服务
        // 开启短事务，并在死锁回滚后使用全新事务重试，避免在 rollback-only 事务中原地重试。
        if (TransactionSynchronizationManager.isActualTransactionActive() || eventTransaction == null) {
            publishInTransaction(projectId, groupId, eventType, resourceId, payload);
            return;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt < EVENT_DEADLOCK_RETRIES; attempt++) {
            try {
                eventTransaction.executeWithoutResult(status ->
                        publishInTransaction(projectId, groupId, eventType, resourceId, payload));
                return;
            } catch (RuntimeException failure) {
                last = failure;
                if (!isDeadlock(failure) || attempt + 1 >= EVENT_DEADLOCK_RETRIES) {
                    throw failure;
                }
                long backoff = EVENT_DEADLOCK_BACKOFF_MS << attempt;
                log.warn("event publish deadlocked; retrying projectId={} eventType={} attempt={} backoffMs={}",
                        projectId, eventType, attempt + 1, backoff);
                sleepBackoff(backoff);
            }
        }
        throw last == null ? new IllegalStateException("event publish failed") : last;
    }

    /** 在当前事务中分配项目序号并写入事件。 */
    private void publishInTransaction(UUID projectId, UUID groupId, String eventType, String resourceId,
                                      Map<String, Object> payload) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EventEntity event = new EventEntity();
        event.setId(UuidV7.next());
        event.setProjectId(projectId);
        event.setRequirementGroupId(groupId);
        // 并发安全：先持有项目行锁，把同项目事件写入串行化；再以锁定读取当前已提交的
        // 最大序号 + 1。缺少项目行锁时，REPEATABLE READ 的普通快照 MAX 会读到旧值，
        // 多个并发发布撞 uk_event_seq 唯一键 → 整体 500（群成员多选邀请等场景）。
        lockProject(projectId);
        event.setSequenceNo(eventMapper.nextSequence(projectId));
        event.setEventType(eventType);
        event.setResourceId(resourceId);
        event.setPayload(payload);
        event.setCreatedAt(now);
        eventMapper.insert(event);
        publishDomainEvent(projectId, eventType, resourceId, payload);
        // 推送目标（契约 2026-08-17 群成员可见性收紧）：
        //   groupId 为空（项目级事件）→ 本项目全部成员；
        //   groupId 为主群 → 本项目全部成员（主群不写入 group_members）；
        //   groupId 为需求群 → 仅该群显式成员。
        Set<UUID> members = broadcastMembers(projectId, groupId);
        fan(members, "project", id(projectId), id(groupId), null, null, resourceId, eventType, payload);
    }

    private boolean isDeadlock(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (current instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("event publish retry interrupted", interrupted);
        }
    }

    /**
     * 持有项目行锁（FOR UPDATE），将同项目的事件写入串行化。
     * <p>
     * {@code uk_event_seq(project_id, sequence_no)} 是项目级作用域，因此锁必须升到
     * 项目级，与消息发送 {@code lockGroup}（群行锁保证消息 sequence 单调）同思路。
     * 调用方须处于事务内（{@code publish} 已标注 {@code @Transactional}）；项目不存在时
     * 返回 null（锁对不存在的行不生效），由后续事件插入的外键/唯一约束兜底。
     */
    private void lockProject(UUID projectId) {
        projectMapper.selectByIdForUpdate(projectId);
    }

    /**
     * 计算事件广播成员集合：项目级事件与 PROJECT_MAIN 主群事件广播全部项目成员；
     * REQUIREMENT 需求群事件仅广播群显式成员（group_members）。
     */
    private Set<UUID> broadcastMembers(UUID projectId, UUID groupId) {
        if (groupId == null) {
            return projectMembers(projectId);
        }
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group != null && "PROJECT_MAIN".equals(group.getGroupType())) {
            return projectMembers(projectId);
        }
        return new java.util.HashSet<>(groupMemberMapper.selectUserIds(groupId));
    }

    private Set<UUID> projectMembers(UUID projectId) {
        return projectMemberMapper.selectMembers(projectId).stream()
                .map(m -> m.getUserId()).collect(Collectors.toSet());
    }

    /**
     * 事件落库后发布进程内领域事件，供主后端内部模块在事务提交后消费（如 MR_FIRST 交付执行器）。
     * 与浏览器 SSE 同源但用途分离：SSE 面向前端展示，领域事件面向内部执行，互为补充。
     * 事务边界由 {@code @TransactionalEventListener(AFTER_COMMIT)} 负责：事务内发布会在提交后消费，
     * 非事务调用则由监听器的 fallbackExecution 立即消费。不得在 afterCommit 回调中再次发布事件，
     * 否则事务监听器没有可注册的事务上下文。
     */
    private void publishDomainEvent(UUID projectId, String eventType, String resourceId, Map<String, Object> payload) {
        if ("delivery.started".equals(eventType) && resourceId != null && payload instanceof Map<?, ?> map
                && map.get("taskId") != null && map.get("operationId") != null) {
            DeliveryStartedDomainEvent domainEvent = new DeliveryStartedDomainEvent(projectId,
                    UUID.fromString(String.valueOf(map.get("taskId"))),
                    payload.get("reviewBatchId") == null ? null : UUID.fromString(String.valueOf(map.get("reviewBatchId"))),
                    String.valueOf(map.get("operationId")));
            publisher.publishEvent(domainEvent);
        }
        if ("mr-first.preflight.requested".equals(eventType) && resourceId != null
                && payload instanceof Map<?, ?> map && map.get("taskId") != null) {
            publisher.publishEvent(new MrFirstPreflightRequestedDomainEvent(projectId,
                    UUID.fromString(String.valueOf(map.get("taskId")))));
        }
        if ("preflight.updated".equals(eventType) && payload != null
                && "APPROVED".equals(String.valueOf(payload.get("decision")))
                && resourceId != null) {
            publisher.publishEvent(new PreflightCqApprovedDomainEvent(projectId, UUID.fromString(resourceId)));
        }
        if ("dry-run.updated".equals(eventType) && payload != null
                && "FAILED".equals(String.valueOf(payload.get("status")))
                && resourceId != null && payload.get("taskId") != null) {
            publisher.publishEvent(new DryRunConflictCandidateDomainEvent(projectId,
                    UUID.fromString(resourceId), UUID.fromString(String.valueOf(payload.get("taskId")))));
        }
    }

    /**
     * 发布一条通知级事件（前端 SSE 需求清单 ③：/notifications/events）。
     * 按用户维度分配单调递增序号；payload 必须为已脱敏内容。
     *
     * @param recipientUserId 接收通知的用户 ID
     * @param notificationId  关联通知 ID（可为 null）
     * @param kind            通知类型
     * @param payload         脱敏事件载荷
     */
    @Transactional
    public void publishNotification(UUID recipientUserId, UUID notificationId, String kind,
                                    Map<String, Object> payload) {
        if (recipientUserId == null) {
            return;
        }
        if (userMapper != null) {
            userMapper.selectByIdForUpdate(recipientUserId);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        NotificationEventEntity event = new NotificationEventEntity();
        event.setId(UuidV7.next());
        event.setRecipientUserId(recipientUserId);
        event.setSequenceNo(notificationEventMapper.maxSequence(recipientUserId) + 1);
        event.setNotificationId(notificationId);
        event.setKind(kind);
        event.setPayload(payload);
        event.setCreatedAt(now);
        notificationEventMapper.insert(event);
        // WebSocket 用户级聚合：仅推送该接收用户（其全部在线连接都收到）
        fan(Set.of(recipientUserId), "notification", null, null, null, id(recipientUserId), id(notificationId),
                kind, payload);
    }

    /**
     * 建立通知级 SSE 事件流（当前用户维度，无需额外鉴权——用户即本人）。
     * 支持 Last-Event-ID 续传；无事件或游标过期时按 409 拒绝（与项目级一致）。
     */
    public SseEmitter notificationStream(UUID userId, Long lastEventId) {
        if (lastEventId != null && lastEventId < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR", "Last-Event-ID 必须为非负序号");
        }
        Long minSeq = null;
        if (lastEventId != null) {
            minSeq = notificationEventMapper.minSequence(userId);
            if (minSeq == null || minSeq > lastEventId) {
                throw new ApiException(HttpStatus.CONFLICT, "EVENT_CURSOR_EXPIRED",
                        "续传点已过期，请重新拉取相关资源");
            }
        }
        long cursor = lastEventId != null ? lastEventId
                : notificationEventMapper.maxSequence(userId);
        if (!acquireSseSlot()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SSE_CONNECTION_LIMIT_EXCEEDED",
                    "当前实时连接过多，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean connectionClosed = new AtomicBoolean();
        emitter.onTimeout(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE notification timeout, userId={}, cursor={}", userId, cursor);
        });
        emitter.onError(e -> {
            closeSseConnection(connectionClosed);
            log.info("SSE notification error, userId={}: {}", userId, e.getMessage());
        });
        emitter.onCompletion(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE notification completed, userId={}, cursor={}", userId, cursor);
        });
        executor.execute(() -> notificationPump(emitter, userId, cursor));
        return emitter;
    }

    private void notificationPump(SseEmitter emitter, UUID userId, long startCursor) {
        long cursor = startCursor;
        LocalDateTime lastHeartbeat = LocalDateTime.now(ZoneOffset.UTC);
        try {
            while (true) {
                List<NotificationEventEntity> events = notificationEventMapper.listAfter(userId, cursor, BATCH_SIZE);
                for (NotificationEventEntity event : events) {
                    if (!send(emitter, SseEmitter.event()
                            .id(String.valueOf(event.getSequenceNo()))
                            .name("notification.created")
                            .data(event.getPayload()))) {
                        return;
                    }
                    cursor = event.getSequenceNo();
                }
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                if (Duration.between(lastHeartbeat, now).getSeconds() >= HEARTBEAT_SECONDS) {
                    if (!send(emitter, SseEmitter.event().comment("heartbeat"))) {
                        return;
                    }
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发布一条团队级事件（前端 SSE 需求清单 ②：/teams/{teamId}/events）。
     * 按团队维度分配单调递增序号；payload 必须为已脱敏内容。
     *
     * @param teamId     所属团队 ID（调用方应已校验团队归属）
     * @param eventType  事件类型（project.member.added/team.member.updated/activity.created）
     * @param resourceId 关联资源 ID（可为 null）
     * @param payload    脱敏事件载荷
     */
    @Transactional
    public void publishTeamEvent(UUID teamId, String eventType, String resourceId, Map<String, Object> payload) {
        if (teamId == null) {
            return;
        }
        if (teamMapper != null) {
            teamMapper.selectByIdForUpdate(teamId);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TeamEventEntity event = new TeamEventEntity();
        event.setId(UuidV7.next());
        event.setTeamId(teamId);
        event.setSequenceNo(teamEventMapper.maxSequence(teamId) + 1);
        event.setEventType(eventType);
        event.setResourceId(resourceId);
        event.setPayload(payload);
        event.setCreatedAt(now);
        teamEventMapper.insert(event);
        // WebSocket 用户级聚合：推送给本团队全部成员（其所有在线连接都收到）
        Set<UUID> members = teamMemberMapper.selectByTeamId(teamId).stream()
                .map(m -> m.getUserId()).collect(Collectors.toSet());
        fan(members, "team", null, null, id(teamId), null, resourceId, eventType, payload);
    }

    /**
     * 建立团队级 SSE 事件流（需调用者为团队成员）。
     * 支持 Last-Event-ID 续传；游标过期返回 409。
     */
    public SseEmitter teamStream(UUID teamId, UUID userId, Long lastEventId) {
        projectAccess.requireTeamMember(teamId, userId);
        if (lastEventId != null && lastEventId < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR", "Last-Event-ID 必须为非负序号");
        }
        Long minSeq = null;
        if (lastEventId != null) {
            minSeq = teamEventMapper.minSequence(teamId);
            if (minSeq == null || minSeq > lastEventId) {
                throw new ApiException(HttpStatus.CONFLICT, "EVENT_CURSOR_EXPIRED",
                        "续传点已过期，请重新拉取相关资源");
            }
        }
        long cursor = lastEventId != null ? lastEventId : teamEventMapper.maxSequence(teamId);
        if (!acquireSseSlot()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SSE_CONNECTION_LIMIT_EXCEEDED",
                    "当前实时连接过多，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean connectionClosed = new AtomicBoolean();
        emitter.onTimeout(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE team timeout, teamId={}, cursor={}", teamId, cursor);
        });
        emitter.onError(e -> {
            closeSseConnection(connectionClosed);
            log.info("SSE team error, teamId={}: {}", teamId, e.getMessage());
        });
        emitter.onCompletion(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE team completed, teamId={}, cursor={}", teamId, cursor);
        });
        executor.execute(() -> teamPump(emitter, teamId, cursor));
        return emitter;
    }

    private void teamPump(SseEmitter emitter, UUID teamId, long startCursor) {
        long cursor = startCursor;
        LocalDateTime lastHeartbeat = LocalDateTime.now(ZoneOffset.UTC);
        try {
            while (true) {
                List<TeamEventEntity> events = teamEventMapper.listAfter(teamId, cursor, BATCH_SIZE);
                for (TeamEventEntity event : events) {
                    if (!send(emitter, SseEmitter.event()
                            .id(String.valueOf(event.getSequenceNo()))
                            .name(event.getEventType())
                            .data(event.getPayload()))) {
                        return;
                    }
                    cursor = event.getSequenceNo();
                }
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                if (Duration.between(lastHeartbeat, now).getSeconds() >= HEARTBEAT_SECONDS) {
                    if (!send(emitter, SseEmitter.event().comment("heartbeat"))) {
                        return;
                    }
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 建立项目级 SSE 事件流连接。
     * 先校验调用者是项目成员；若提供 Last-Event-ID 且该游标已超出 24 小时保留窗口
     * （无事件或最小保留序号大于游标），抛出 409 EVENT_CURSOR_EXPIRED 拒绝续传。
     * 连接后每 2 秒轮询增量事件推送，每 15 秒发送心跳；断线或超时后自动清理。
     *
     * @param projectId   所属项目ID
     * @param userId      已认证用户ID，用于成员鉴权
     * @param lastEventId Last-Event-ID 续传游标（项目内事件序号），无续传需求时为 null
     * @return 已建立的 SseEmitter，事件以 eventType 作为 event name、sequenceNo 作为 id 推送
     */
    public SseEmitter stream(UUID projectId, UUID userId, Long lastEventId) {
        projectAccess.requireProjectMember(projectId, userId);
        if (lastEventId != null && lastEventId < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR", "Last-Event-ID 必须为非负序号");
        }
        Long minSeq = null;
        if (lastEventId != null) {
            minSeq = eventMapper.minSequence(projectId);
            if (minSeq == null || minSeq > lastEventId) {
                // 游标超出保留窗口：事件已过期或从未存在，拒绝续传
                throw new ApiException(HttpStatus.CONFLICT, "EVENT_CURSOR_EXPIRED",
                        "续传点已过期，请重新拉取相关资源");
            }
        }
        long cursor = lastEventId != null ? lastEventId : eventMapper.maxSequence(projectId);

        if (!acquireSseSlot()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SSE_CONNECTION_LIMIT_EXCEEDED",
                    "当前实时连接过多，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean connectionClosed = new AtomicBoolean();

        emitter.onTimeout(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE timeout, projectId={}, cursor={}", projectId, cursor);
        });
        emitter.onError(e -> {
            closeSseConnection(connectionClosed);
            log.info("SSE error, projectId={}: {}", projectId, e.getMessage());
        });
        emitter.onCompletion(() -> {
            closeSseConnection(connectionClosed);
            log.info("SSE completed, projectId={}, cursor={}", projectId, cursor);
        });

        // 群成员可见性（契约 2026-08-17 严格收紧）：SSE 仅推送用户可见群（主群 + 已加入需求群）的事件
        Set<UUID> visibleGroups = new HashSet<>();
        groupMapper.selectList(Wrappers.<RequirementGroupEntity>lambdaQuery()
                        .eq(RequirementGroupEntity::getProjectId, projectId)
                        .eq(RequirementGroupEntity::getGroupType, "PROJECT_MAIN"))
                .forEach(group -> visibleGroups.add(group.getId()));
        visibleGroups.addAll(groupMemberMapper.selectGroupIdsByUser(projectId, userId));

        executor.execute(() -> pump(emitter, projectId, cursor, visibleGroups, connectionClosed));
        return emitter;
    }

    /**
     * 轮询推送增量事件。线程退出条件：客户端断开/超时导致 send 失败，或线程被中断。
     * 游标本地推进，成功发送的事件序号即新的续传点；不可见群的事件跳过发送但同样推进游标
     * （不可见事件无需重试，避免重复拉取同一批）。
     */
    private void pump(SseEmitter emitter, UUID projectId, long startCursor, Set<UUID> visibleGroups,
                      AtomicBoolean connectionClosed) {
        long cursor = startCursor;
        LocalDateTime lastHeartbeat = LocalDateTime.now(ZoneOffset.UTC);
        try {
            while (true) {
                Timer.Sample pollTimer = metrics == null ? null : metrics.start();
                List<EventEntity> events = eventMapper.listAfter(projectId, cursor, BATCH_SIZE);
                if (metrics != null) {
                    metrics.stop(pollTimer, "qgents.sse.poll.duration", "event_poll", "succeeded");
                }
                for (EventEntity event : events) {
                    cursor = event.getSequenceNo();
                    if (event.getRequirementGroupId() == null
                            || visibleGroups.contains(event.getRequirementGroupId())) {
                        if (!send(emitter, SseEmitter.event()
                                .id(String.valueOf(event.getSequenceNo()))
                                .name(event.getEventType())
                                .data(event.getPayload()))) {
                            return;
                        }
                    }
                }
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                if (Duration.between(lastHeartbeat, now).getSeconds() >= HEARTBEAT_SECONDS) {
                    if (!send(emitter, SseEmitter.event().comment("heartbeat"))) {
                        return;
                    }
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSseConnection(connectionClosed);
        }
    }

    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            if (metrics != null) {
                metrics.increment("qgents.sse.send.failures", "event_send", "failed");
            }
            // 客户端断开/连接失效（含 AsyncRequestNotUsableException，其亦为 IOException 子类）：
            // 立即结束该 emitter，泵线程退出，等待客户端按 Last-Event-ID 重连
            completeQuietly(emitter);
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接已断时 complete 自身也可能抛错，忽略
        }
    }

    /**
     * 尝试占用一个 SSE 连接槽位。超过 {@link #MAX_SSE_CONNECTIONS} 时返回 false，
     * 调用方应拒绝建立新连接（429），避免连接堆积耗尽泵线程（固定 8 线程池排队）
     * 与数据库连接（每个泵线程周期性查询事件表）。
     */
    private boolean acquireSseSlot() {
        while (true) {
            int current = activeSseConnections.get();
            if (current >= MAX_SSE_CONNECTIONS) {
                log.warn("SSE connection limit reached, active={} max={}", current, MAX_SSE_CONNECTIONS);
                return false;
            }
            if (activeSseConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void closeSseConnection(AtomicBoolean connectionClosed) {
        if (connectionClosed.compareAndSet(false, true)) {
            activeSseConnections.decrementAndGet();
        }
    }

    /**
     * 将一条已落库事件按目标用户聚合推送到其全部在线 WebSocket 连接（单连接 + 用户级聚合流）。
     * <p>
     * 实时推送失败只记 warn，不向调用方（可能处于业务事务中）抛出，确保 WS fan-out 不影响业务写入；
     * 事件仅作「刷新界面」信号，不在此续传（断线后由前端以 REST 兜底）。
     *
     * @param userIds      目标用户 ID 集（项目成员 / 团队成员 / 通知接收人）
     * @param scope        作用域：project / team / notification
     * @param projectId    项目 ID（scope=project 时非空）
     * @param groupId      关联需求群 ID（可为 null）
     * @param teamId       团队 ID（scope=team 时非空）
     * @param recipientUserId 通知接收人 ID（scope=notification 时非空）
     * @param resourceId   关联资源 ID（可为 null）
     * @param eventType    事件类型（对应 SSE eventType）
     * @param payload      脱敏业务载荷
     */
    private void fan(Set<UUID> userIds, String scope, String projectId, String groupId, String teamId,
                     String recipientUserId, String resourceId, String eventType, Map<String, Object> payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务提交前浏览器若据 WS 帧立即查询，可能读不到尚未提交的事件/消息。
            // 只在提交成功后 fan-out，回滚时不发布易失刷新信号。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast(userIds, scope, projectId, groupId, teamId, recipientUserId, resourceId,
                            eventType, payload);
                }
            });
            return;
        }
        broadcast(userIds, scope, projectId, groupId, teamId, recipientUserId, resourceId, eventType, payload);
    }

    private void broadcast(Set<UUID> userIds, String scope, String projectId, String groupId, String teamId,
                           String recipientUserId, String resourceId, String eventType, Map<String, Object> payload) {
        try {
            realtimeHub.broadcastToUsers(userIds, RealtimeFrame.of(eventType, scope, projectId, groupId,
                    teamId, recipientUserId, resourceId, payload));
        } catch (Exception e) {
            log.warn("realtime fan-out failed, scope={}, eventType={}: {}", scope, eventType, e.getMessage());
        }
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    /**
     * 每日清理全库 24 小时前的过期事件。清理不参与事件发布所在的业务事务，避免清理死锁
     * 回滚 TaskRun、TaskStep 等关键状态写入。
     * 依赖 Qgents 全局 {@code @EnableScheduling} 生效。
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpired() {
        try {
            int removed = eventMapper.deleteExpired(LocalDateTime.now(ZoneOffset.UTC).minusHours(RETENTION_HOURS));
            if (removed > 0) {
                log.info("purged {} expired events", removed);
            }
        } catch (Exception e) {
            log.warn("event purge failed: {}", e.getMessage());
        }
    }
}
