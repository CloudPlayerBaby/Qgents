package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.NotificationResponse;
import qg.qgent.entity.NotificationEntity;
import qg.qgent.mapper.NotificationMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通知中心业务（A 联调约定 §1）。
 * <p>
 * 通知按用户维度持久化：由 Task/编排、Diff、MR 等事件触发 {@link #notify} 写入，
 * 用户通过列表与已读接口读取；SSE 只负责实时提醒，不承担历史列表与已读状态。
 * 已读标记为幂等操作，重复标记不影响结果。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final EventService eventService;

    public NotificationService(NotificationMapper notificationMapper, EventService eventService) {
        this.notificationMapper = notificationMapper;
        this.eventService = eventService;
    }

    /**
     * 查询当前用户的通知列表，按产生时间倒序（A 约定一次性返回全量，不分页）。
     *
     * @param userId 当前登录用户 ID
     * @return 该用户的全部通知视图，新的在前
     */
    public List<NotificationResponse> list(UUID userId) {
        return notificationMapper.listByRecipient(userId).stream().map(this::toResponse).toList();
    }

    /**
     * 标记单条通知已读；通知不存在或不属于当前用户时返回 404。
     *
     * @param userId         当前登录用户 ID
     * @param notificationId 通知 ID
     */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        NotificationEntity entity = notificationMapper.selectById(notificationId);
        if (entity == null || !entity.getRecipientUserId().equals(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在或无权访问");
        }
        notificationMapper.markRead(notificationId, userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 将当前用户的全部未读通知标记为已读（幂等）。
     *
     * @param userId 当前登录用户 ID
     */
    @Transactional
    public void markAllRead(UUID userId) {
        notificationMapper.markAllRead(userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 写入一条通知（事件触发入口）。
     * <p>
     * 接收人统一为任务发起人（Task.createdBy）；调用方必须已通过项目/资源归属校验。
     *
     * @param recipientUserId 接收通知的用户 ID
     * @param projectId       关联项目 ID，可为空
     * @param groupId         来源需求群 ID，可为空
     * @param kind            通知类型枚举（见 {@link NotificationEntity}）
     * @param title           一行标题
     * @param description     补充说明，可为空
     * @param resourceId      关联资源 ID 字符串，可为空
     */
    public void notify(UUID recipientUserId, UUID projectId, UUID groupId, String kind, String title,
                       String description, String resourceId) {
        if (recipientUserId == null) {
            return;
        }
        NotificationEntity entity = new NotificationEntity();
        entity.setId(UuidV7.next());
        entity.setRecipientUserId(recipientUserId);
        entity.setProjectId(projectId);
        entity.setRequirementGroupId(groupId);
        entity.setKind(kind);
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setResourceId(resourceId);
        entity.setIsRead(false);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        notificationMapper.insert(entity);
        // 通知级 SSE：新通知信号（前端 SSE 需求清单 ③），事件名 notification.created
        eventService.publishNotification(recipientUserId, entity.getId(), kind,
                Map.of("notificationId", id(entity.getId()), "kind", kind));
    }

    private NotificationResponse toResponse(NotificationEntity n) {
        return new NotificationResponse(n.getId().toString(), n.getKind(), n.getTitle(), n.getDescription(),
                n.getIsRead(), iso(n.getCreatedAt()), id(n.getProjectId()), id(n.getRequirementGroupId()),
                n.getResourceId());
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
