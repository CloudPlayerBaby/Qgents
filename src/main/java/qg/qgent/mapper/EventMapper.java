package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.EventEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface EventMapper extends BaseMapper<EventEntity> {

    /** 获取项目内当前最大事件序号；无事件时返回 0。 */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM events WHERE project_id = #{projectId}")
    long maxSequence(@Param("projectId") UUID projectId);

    /** 获取项目内最小事件序号；无事件时返回 null，用于校验 SSE 续传游标是否仍在保留窗口内。 */
    @Select("SELECT MIN(sequence_no) FROM events WHERE project_id = #{projectId}")
    Long minSequence(@Param("projectId") UUID projectId);

    /** 拉取项目内序号大于游标的事件，按序号升序，用于 SSE 增量推送。 */
    @Select("SELECT id, project_id, requirement_group_id, sequence_no, event_type, resource_id, payload, created_at "
            + "FROM events WHERE project_id = #{projectId} AND sequence_no > #{after} "
            + "ORDER BY sequence_no ASC LIMIT #{limit}")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "requirement_group_id", property = "requirementGroupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "sequence_no", property = "sequenceNo"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "resource_id", property = "resourceId"),
            @Result(column = "payload", property = "payload", typeHandler = JacksonTypeHandler.class),
            @Result(column = "created_at", property = "createdAt")
    })
    List<EventEntity> listAfter(@Param("projectId") UUID projectId, @Param("after") long after,
            @Param("limit") int limit);

    /** 删除指定项目早于 cutoff 的过期事件（发布时顺带清理）。 */
    @Delete("DELETE FROM events WHERE project_id = #{projectId} AND created_at < #{cutoff}")
    int deleteBefore(@Param("projectId") UUID projectId, @Param("cutoff") LocalDateTime cutoff);

    /** 删除全部早于 cutoff 的过期事件（每日定时清理）。 */
    @Delete("DELETE FROM events WHERE created_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
