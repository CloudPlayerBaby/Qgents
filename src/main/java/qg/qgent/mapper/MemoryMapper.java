package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/**
 * Memory 数据访问。
 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {

    /**
     * 项目内查询 Memory：默认仅 APPROVED；非 APPROVED 状态仅创建者或 Admin 可见；支持状态、标签过滤。
     * <p>
     * 自定义 {@code SELECT *} 需要显式 {@code @Results} 才能应用 JacksonTypeHandler
     * 反序列化 tags JSON 列与 UuidBinaryTypeHandler 处理 BINARY(16) UUID 列。
     *
     * @param projectId 项目 ID
     * @param actor     当前用户 ID
     * @param isAdmin   当前用户是否为项目 Admin（Team Owner 兜底视为 Admin）
     * @param status    状态过滤，可为空（为空即默认 APPROVED）
     * @param tag       标签过滤，可为空
     * @return Memory 列表，按更新时间倒序
     */
    @Select({"<script>",
            "SELECT * FROM memories WHERE project_id = #{projectId}",
            "AND (status = 'APPROVED' OR created_by = #{actor} OR #{isAdmin})",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "ORDER BY updated_at DESC",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "submitted_by", property = "submittedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reviewer_id", property = "reviewerId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
    })
    List<MemoryEntity> listMemories(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                    @Param("isAdmin") boolean isAdmin, @Param("status") String status, @Param("tag") String tag);

    /**
     * 按关键字检索项目内已批准的 Memory（点6 上下文检索）。
     * <p>
     * 状态固定 APPROVED（按接口文档 §9 语义，Agent 只能检索已批准 Memory）。
     * 关键字匹配 title 或 content（LIKE），标签用 JSON_CONTAINS 精确匹配。
     *
     * @param projectId 项目 ID
     * @param actor     当前用户 ID
     * @param isAdmin   当前用户是否为项目 Admin（Team Owner 兜底视为 Admin）
     * @param tag       标签过滤，可为空
     * @param q         关键字，可为空（为空时退化为全部已批准）
     * @return 匹配的 Memory 实体列表，按更新时间倒序
     */
    @Select({"<script>",
            "SELECT * FROM memories WHERE project_id = #{projectId}",
            "AND status = 'APPROVED'",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "<if test='q != null'>AND (title LIKE CONCAT('%', #{q}, '%') OR content LIKE CONCAT('%', #{q}, '%'))</if>",
            "ORDER BY updated_at DESC",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "submitted_by", property = "submittedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reviewer_id", property = "reviewerId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
    })
    List<MemoryEntity> searchByQuery(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                     @Param("isAdmin") boolean isAdmin, @Param("tag") String tag, @Param("q") String q);

    /**
     * 锁定读取项目内全部已批准 Memory。
     * <p>
     * 调用方须先锁定对应 {@code projects} 行，再在同一事务内调用本方法并完成状态变更，
     * 以避免批准预算的“聚合读取后并发写入”竞态。锁定读不复用事务中的一致性读快照，
     * 因此可看到前一笔批准或归档提交后的最新状态。
     *
     * @param projectId 项目 ID
     * @return 已锁定的已批准 Memory
     */
    @Select("SELECT * FROM memories WHERE project_id = #{projectId} AND status = 'APPROVED' FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "submitted_by", property = "submittedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reviewer_id", property = "reviewerId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
    })
    List<MemoryEntity> selectApprovedForUpdate(@Param("projectId") UUID projectId);

    /**
     * 锁定读取单条 Memory，用于在状态迁移前获得当前已提交状态。
     *
     * @param memoryId Memory ID
     * @return 已锁定的 Memory；不存在时返回 {@code null}
     */
    @Select("SELECT * FROM memories WHERE id = #{memoryId} FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", id = true, typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "submitted_by", property = "submittedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reviewer_id", property = "reviewerId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "tags", property = "tags", typeHandler = JacksonTypeHandler.class)
    })
    MemoryEntity selectByIdForUpdate(@Param("memoryId") UUID memoryId);
}
