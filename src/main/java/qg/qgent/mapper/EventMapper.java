package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.*;
import qg.qgent.entity.EventEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface EventMapper extends BaseMapper<EventEntity> {

    /**
     * 获取项目内当前最大事件序号；无事件时返回 0。
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM events WHERE project_id = #{projectId}")
    long maxSequence(@Param("projectId") UUID projectId);

    /**
     * 以锁定读方式计算项目内下一个事件序号（并发安全，供事件发布专用）。
     * <p>
     * {@code FOR UPDATE} 对 {@code uk_event_seq(project_id, sequence_no)} 索引范围加排他
     * next-key 锁并读取**当前已提交**的最大序号 + 1。调用方必须先在事务内持有对应
     * projects 行锁（{@link qg.qgent.mapper.ProjectMapper#selectByIdForUpdate}）：同项目
     * 事件写入串行化后，本锁定读才能拿到最新值，避免 REPEATABLE READ 快照读到旧序号后
     * 与并发发布撞 {@code uk_event_seq} 唯一键（群成员多选邀请等并发写 500 的根因）。
     *
     * @param projectId 项目 ID
     * @return 下一个事件序号（当前已提交最大序号 + 1；无事件时为 1）
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM events WHERE project_id = #{projectId} FOR UPDATE")
    long nextSequence(@Param("projectId") UUID projectId);

    /**
     * 获取项目内最小事件序号；无事件时返回 null，用于校验 SSE 续传游标是否仍在保留窗口内。
     */
    @Select("SELECT MIN(sequence_no) FROM events WHERE project_id = #{projectId}")
    Long minSequence(@Param("projectId") UUID projectId);

    /**
     * 拉取项目内序号大于游标的事件，按序号升序，用于 SSE 增量推送。
     */
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

    /**
     * 拉取团队内所有项目的最远动态事件（events JOIN projects 按 team_id 过滤）。
     * <p>
     * 按事件 id（UUIDv7，含时间序）倒序做 keyset 分页；fragments 为服务端白名单拼装的
     * 动态类型过滤片段（如 event_type 与 payload.status 的 JSON 取值约束），仅来自固定映射表，
     * 不含任何用户输入。anchor 为上一页最后一条事件 id，返回 id 小于 anchor 的一页。
     *
     * @param teamId    团队 ID
     * @param anchor    上一页游标事件 id，可为 null
     * @param fragments 动态类型过滤 SQL 片段（OR 连接）；为空时返回空集
     * @param limit     本次拉取条数上限
     * @return 该团队事件页（按 id 倒序）
     */
    @Select({"<script>",
            "SELECT e.id, e.project_id, e.requirement_group_id, e.sequence_no, e.event_type, e.resource_id, e.payload, e.created_at",
            "FROM events e JOIN projects p ON e.project_id = p.id",
            "WHERE p.team_id = #{teamId}",
            "<if test='anchor != null'>AND e.id &lt; #{anchor}</if>",
            "AND (<foreach collection='fragments' item='f' separator=' OR '>${f}</foreach>)",
            "ORDER BY e.id DESC LIMIT #{limit}",
            "</script>"})
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
    List<EventEntity> listTeamAfter(@Param("teamId") UUID teamId, @Param("anchor") UUID anchor,
                                    @Param("fragments") List<String> fragments, @Param("limit") int limit);

    /**
     * 删除全部早于 cutoff 的过期事件（每日定时清理）。
     */
    @Delete("DELETE FROM events WHERE created_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
