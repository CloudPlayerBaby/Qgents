package qg.qgent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.config.MdcTaskDecorator;
import qg.qgent.entity.EventEntity;
import qg.qgent.entity.NotificationEventEntity;
import qg.qgent.entity.TeamEventEntity;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.NotificationEventMapper;
import qg.qgent.mapper.TeamEventMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目级实时事件（SSE 数据源）服务。
 * 写接口在状态变更时调用 {@link #publish} 持久化事件，客户端通过项目事件流订阅；
 * 事件至少保留 24 小时，支持 Last-Event-ID 断线续传，游标过期返回 409 EVENT_CURSOR_EXPIRED。
 * publish 以「项目内 MAX(sequence_no)+1」分配序号并落库（并发写存在极小竞态，MVP 可接受，
 * 后续可换 Redis 原子计数器）；stream 用独立轮询线程拉取增量事件，通过 SseEmitter 推送，
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

    private final EventMapper eventMapper;
    private final ProjectAccessService projectAccess;
    private final NotificationEventMapper notificationEventMapper;
    private final TeamEventMapper teamEventMapper;
    private final ExecutorService executor;

    public EventService(EventMapper eventMapper, ProjectAccessService projectAccess,
                        NotificationEventMapper notificationEventMapper, TeamEventMapper teamEventMapper) {
        this.eventMapper = eventMapper;
        this.projectAccess = projectAccess;
        this.notificationEventMapper = notificationEventMapper;
        this.teamEventMapper = teamEventMapper;
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
    }

    /**
     * 发布一条项目级事件并持久化。
     * 事件以项目内单调递增的 sequenceNo 排序，作为 SSE 游标；payload 必须为已脱敏内容，
     * 禁止包含 Token、密码、私钥等敏感信息。发布时顺带清理本项目 24 小时前的过期事件。
     *
     * @param projectId  所属项目ID（调用方应已校验项目成员身份）
     * @param groupId    可选关联需求群ID，可为 null
     * @param eventType  事件类型，如 task-run.updated / diff.created / merge-request.updated
     * @param resourceId 关联资源ID字符串，如 taskRunId，可为 null
     * @param payload    脱敏事件载荷 JSON
     */
    public void publish(UUID projectId, UUID groupId, String eventType, String resourceId,
                        Map<String, Object> payload) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EventEntity event = new EventEntity();
        event.setId(UuidV7.next());
        event.setProjectId(projectId);
        event.setRequirementGroupId(groupId);
        event.setSequenceNo(eventMapper.maxSequence(projectId) + 1);
        event.setEventType(eventType);
        event.setResourceId(resourceId);
        event.setPayload(payload);
        event.setCreatedAt(now);
        eventMapper.insert(event);
        try {
            eventMapper.deleteBefore(projectId, now.minusHours(RETENTION_HOURS));
        } catch (Exception e) {
            // 清理失败不影响事件发布，留待每日定时任务兜底
            log.warn("event cleanup skipped: {}", e.getMessage());
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
    public void publishNotification(UUID recipientUserId, UUID notificationId, String kind,
                                    Map<String, Object> payload) {
        if (recipientUserId == null) {
            return;
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
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> log.info("SSE notification timeout, userId={}, cursor={}", userId, cursor));
        emitter.onError(e -> log.info("SSE notification error, userId={}: {}", userId, e.getMessage()));
        emitter.onCompletion(() -> log.info("SSE notification completed, userId={}, cursor={}", userId, cursor));
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
    public void publishTeamEvent(UUID teamId, String eventType, String resourceId, Map<String, Object> payload) {
        if (teamId == null) {
            return;
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
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> log.info("SSE team timeout, teamId={}, cursor={}", teamId, cursor));
        emitter.onError(e -> log.info("SSE team error, teamId={}: {}", teamId, e.getMessage()));
        emitter.onCompletion(() -> log.info("SSE team completed, teamId={}, cursor={}", teamId, cursor));
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

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onTimeout(() -> log.info("SSE timeout, projectId={}, cursor={}", projectId, cursor));
        emitter.onError(e -> log.info("SSE error, projectId={}: {}", projectId, e.getMessage()));
        emitter.onCompletion(() -> log.info("SSE completed, projectId={}, cursor={}", projectId, cursor));

        executor.execute(() -> pump(emitter, projectId, cursor));
        return emitter;
    }

    /**
     * 轮询推送增量事件。线程退出条件：客户端断开/超时导致 send 失败，或线程被中断。
     * 游标本地推进，成功发送的事件序号即新的续传点。
     */
    private void pump(SseEmitter emitter, UUID projectId, long startCursor) {
        long cursor = startCursor;
        LocalDateTime lastHeartbeat = LocalDateTime.now(ZoneOffset.UTC);
        try {
            while (true) {
                List<EventEntity> events = eventMapper.listAfter(projectId, cursor, BATCH_SIZE);
                for (EventEntity event : events) {
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

    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
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
     * 每日清理全库 24 小时前的过期事件，兜底发布时的顺带清理。
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
