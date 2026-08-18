package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.SkillEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/**
 * Skill 数据访问。
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /**
     * 项目内查询 Skill：仅返回 PROJECT_SHARED 或自己创建的；支持状态、标签过滤，按更新时间倒序。
     * <p>
     * 自定义 {@code SELECT *} 需要显式 {@code @Results} 才能应用 JacksonTypeHandler
     * 反序列化 tags JSON 列与 UuidBinaryTypeHandler 处理 BINARY(16) UUID 列。
     *
     * @param projectId 项目 ID
     * @param actor     当前用户 ID（用于可见性过滤）
     * @param status    状态过滤，可为空
     * @param tag       标签过滤，可为空
     * @return Skill 列表
     */
    @Select({"<script>",
            "SELECT * FROM skills WHERE project_id = #{projectId}",
            "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor})",
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
    List<SkillEntity> listSkills(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                 @Param("status") String status, @Param("tag") String tag);

    /**
     * 按关键字检索项目内可用的已发布 Skill（点6 上下文检索）。
     * <p>
     * 可见性与 listSkills 一致：仅 PROJECT_SHARED 或自己创建；状态固定 PUBLISHED。
     * 关键字匹配 name 或 content（LIKE），标签用 JSON_CONTAINS 精确匹配。
     *
     * @param projectId 项目 ID
     * @param actor     当前用户 ID（用于可见性过滤）
     * @param tag       标签过滤，可为空
     * @param q         关键字，可为空（为空时退化为全部已发布）
     * @return 匹配的 Skill 实体列表，按更新时间倒序
     */
    @Select({"<script>",
            "SELECT * FROM skills WHERE project_id = #{projectId}",
            "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor})",
            "AND status = 'PUBLISHED'",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "<if test='q != null'>AND (name LIKE CONCAT('%', #{q}, '%') OR content LIKE CONCAT('%', #{q}, '%'))</if>",
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
    List<SkillEntity> searchByQuery(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
                                    @Param("tag") String tag, @Param("q") String q);
}
