package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.entity.IdempotencyRecordEntity;

import java.time.LocalDateTime;
import java.util.UUID;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {
    @Delete("DELETE FROM idempotency_records WHERE id = #{id} AND request_hash = #{requestHash} "
            + "AND response_body_redacted IS NULL AND created_at <= #{cutoff}")
    int deleteStaleClaim(@Param("id") UUID id, @Param("requestHash") byte[] requestHash,
            @Param("cutoff") LocalDateTime cutoff);

    @Delete("DELETE FROM idempotency_records WHERE id = #{id} AND request_hash = #{requestHash} "
            + "AND response_body_redacted IS NULL")
    int deletePendingClaim(@Param("id") UUID id, @Param("requestHash") byte[] requestHash);

    @org.apache.ibatis.annotations.Update("UPDATE idempotency_records SET response_status = #{responseStatus}, "
            + "response_body_redacted = #{responseBody} WHERE id = #{id} AND request_hash = #{requestHash} "
            + "AND response_body_redacted IS NULL")
    int completeClaim(@Param("id") UUID id, @Param("requestHash") byte[] requestHash,
            @Param("responseStatus") int responseStatus, @Param("responseBody") String responseBody);
}
