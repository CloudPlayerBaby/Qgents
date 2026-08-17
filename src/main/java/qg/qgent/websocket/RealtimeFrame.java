package qg.qgent.websocket;

import java.time.Instant;
import java.util.Map;

/**
 * WebSocket 实时推送信封（单连接 + 用户级聚合流）。
 * <p>
 * 服务端将当前用户可见的项目/团队事件与本人通知聚合后，以一条 JSON 文本帧推送。
 * 事件仅作为「界面刷新」信号（REST 存真相，前端收到后重新拉取相关资源），
 * 不承担持久化游标续传（续传仍走现有 SSE 的 Last-Event-ID 或查询接口兜底）。
 * payload 复用于 SSE publish 的脱敏载荷，禁止包含 Token、私钥、宿主机路径或未脱敏命令输出。
 *
 * @param type     事件类型，如 message.created / task.updated（对应 SSE 的 eventType）
 * @param scope    作用域：project / team / notification
 * @param projectId 项目 ID（scope=project 时非空，否则 null）
 * @param groupId  关联需求群 ID（可为 null）
 * @param teamId   团队 ID（scope=team 时非空，否则 null）
 * @param recipientUserId 接收通知的用户 ID（scope=notification 时非空，否则 null）
 * @param resourceId 关联资源 ID 字符串（如 messageId/taskId），可为 null
 * @param payload  脱敏业务载荷（与 SSE data 一致）
 * @param sentAt   推送时间（ISO8601 UTC）
 */
public record RealtimeFrame(
        String type,
        String scope,
        String projectId,
        String groupId,
        String teamId,
        String recipientUserId,
        String resourceId,
        Map<String, Object> payload,
        Instant sentAt) {

    /**
     * 构造一条实时推送帧。
     *
     * @param type          事件类型，对应 SSE eventType
     * @param scope         作用域：project / team / notification
     * @param projectId     项目 ID（可为 null）
     * @param groupId       需求群 ID（可为 null）
     * @param teamId        团队 ID（可为 null）
     * @param recipientUserId 接收通知的用户 ID（可为 null）
     * @param resourceId    关联资源 ID（可为 null）
     * @param payload       脱敏载荷（可为 null，推送时按 {} 处理）
     * @return 实时推送帧
     */
    public static RealtimeFrame of(String type, String scope, String projectId, String groupId, String teamId,
                                   String recipientUserId, String resourceId, Map<String, Object> payload) {
        return new RealtimeFrame(type, scope, projectId, groupId, teamId, recipientUserId, resourceId,
                payload == null ? Map.of() : payload, Instant.now());
    }
}
