package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标记已读接口响应：进群全读后返回推进后的已读游标与清零的未读数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupReadResponse {

    /**
     * 需求群 ID。
     */
    @Schema(description = "需求群 ID")
    private String groupId;

    /**
     * 推进后的已读游标（= 该群最新消息 sequence）。
     */
    @Schema(description = "推进后的已读游标（= 该群最新消息 sequence）")
    private Long lastReadSequenceNo;

    /**
     * 未读数，恒为 0（推进后）。
     */
    @Schema(description = "未读数，恒为 0（推进后）")
    private Long unreadCount;
}