package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 群成员已读游标（未读数=群最新sequence-游标，排除本人）。
 * <p>
 * 按「用户 × 群」持久化，游标只前进不后退（进群全读语义）。未读数 = 该群消息
 * sequence_no &gt; lastReadSequenceNo 且非本人 的消息数；lastReadSequenceNo 为 NULL 视为 0。
 */
@Data
@TableName("group_read_state")
public class GroupReadStateEntity {

    /**
     * 用户 ID。
     */
    private UUID userId;

    /**
     * 需求群 ID。
     */
    private UUID groupId;

    /**
     * 已读游标（群内消息序号，BIGINT UNSIGNED）；NULL 视为 0。
     */
    private Long lastReadSequenceNo;

    /**
     * 更新时间（UTC）。
     */
    private LocalDateTime updatedAt;
}