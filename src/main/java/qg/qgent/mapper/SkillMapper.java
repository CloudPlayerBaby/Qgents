package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.SkillEntity;

import java.util.List;
import java.util.UUID;

/**
 * Skill 数据访问。
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /**
     * 项目内查询 Skill：仅返回 PROJECT_SHARED 或自己创建的；支持状态、标签过滤，按更新时间倒序。
     *
     * @param projectId 项目 ID
     * @param actor     当前用户 ID（用于可见性过滤）
     * @param status    状态过滤，可为空
     * @param tag       标签过滤，可为空
     * @return Skill 列表
     */
    @Select({ "<script>",
            "SELECT * FROM skills WHERE project_id = #{projectId}",
            "AND (visibility = 'PROJECT_SHARED' OR created_by = #{actor})",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "ORDER BY updated_at DESC",
            "</script>" })
    List<SkillEntity> listSkills(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
            @Param("status") String status, @Param("tag") String tag);
}
