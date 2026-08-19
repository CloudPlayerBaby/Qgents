package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.PushDeliveryEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 推送 Outbox 数据访问；认领使用条件更新防止多实例重复发送。 */
@Mapper
public interface PushDeliveryMapper extends BaseMapper<PushDeliveryEntity> {
    @Select("SELECT * FROM push_deliveries WHERE status IN ('PENDING','FAILED') "
            + "AND attempt_count < #{maxAttempts} AND next_attempt_at <= #{now} ORDER BY next_attempt_at,id LIMIT #{limit}")
    List<PushDeliveryEntity> selectDue(@Param("now") LocalDateTime now,
                                       @Param("maxAttempts") int maxAttempts,
                                       @Param("limit") int limit);

    @Update("UPDATE push_deliveries SET status='SENDING', attempt_count=attempt_count+1, updated_at=#{now} "
            + "WHERE id=#{id} AND status IN ('PENDING','FAILED') AND next_attempt_at <= #{now}")
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Update("UPDATE push_deliveries SET status='SENT', provider_message_id=#{providerMessageId}, "
            + "last_error_code=NULL, sent_at=#{now}, updated_at=#{now} WHERE id=#{id} AND status='SENDING'")
    int markSent(@Param("id") UUID id, @Param("providerMessageId") String providerMessageId,
                 @Param("now") LocalDateTime now);

    @Update("UPDATE push_deliveries SET status='FAILED', last_error_code=#{errorCode}, "
            + "next_attempt_at=#{nextAttemptAt}, updated_at=#{now} WHERE id=#{id} AND status='SENDING'")
    int markFailed(@Param("id") UUID id, @Param("errorCode") String errorCode,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("now") LocalDateTime now);

    @Update("UPDATE push_deliveries SET status='FAILED', last_error_code='PUSH_WORKER_INTERRUPTED', "
            + "next_attempt_at=#{now}, updated_at=#{now} WHERE status='SENDING' AND updated_at < #{staleBefore}")
    int recoverStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);
}
