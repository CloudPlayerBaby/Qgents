package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.NotificationEventEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 通知级 SSE 事件数据访问（用户维度游标）。
 */
@Mapper
public interface NotificationEventMapper extends BaseMapper<NotificationEventEntity> {

    /**
     * 用户当前最大事件序号；无事件时返回 0。
     */
    @Select("select coalesce(max(sequence_no), 0) from notification_events where recipient_user_id=#{userId}")
    Long maxSequence(@Param("userId") UUID userId);

    /**
     * 用户最小事件序号；无事件时返回 null。
     */
    @Select("select min(sequence_no) from notification_events where recipient_user_id=#{userId}")
    Long minSequence(@Param("userId") UUID userId);

    /**
     * 拉取用户某游标之后的增量事件。
     */
    @Select("select * from notification_events where recipient_user_id=#{userId} and sequence_no > #{after} "
            + "order by sequence_no asc limit #{limit}")
    List<NotificationEventEntity> listAfter(@Param("userId") UUID userId, @Param("after") long after,
                                            @Param("limit") int limit);

    /**
     * 删除某用户某时间之前的过期事件（保留期兜底）。
     */
    @Select("delete from notification_events where recipient_user_id=#{userId} and created_at < #{before}")
    int deleteBefore(@Param("userId") UUID userId, @Param("before") LocalDateTime before);
}
