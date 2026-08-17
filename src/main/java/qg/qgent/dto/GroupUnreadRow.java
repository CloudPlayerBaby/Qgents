package qg.qgent.dto;

import lombok.Data;

import java.util.UUID;

/**
 * 群未读计数查询结果行：{@code MessageMapper.countUnreadByProject} 的映射结果。
 * <p>
 * 仅返回未读数 &gt; 0 的群；由 Service 转换为 {@code groupId → unread} 映射，
 * 对无记录群补 0。
 */
@Data
public class GroupUnreadRow {

    /**
     * 所属需求群 ID。
     */
    private UUID groupId;

    /**
     * 该群未读消息数（sequence_no &gt; 已读游标 且非本人）。
     */
    private Long unread;
}