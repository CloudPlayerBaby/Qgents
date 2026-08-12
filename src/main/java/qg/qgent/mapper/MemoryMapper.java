package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.MemoryEntity;

import java.util.List;
import java.util.UUID;

/**
 * Memory 数据访问。
 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {

    /**
     * 项目内查询 Memory：默认仅 APPROVED；非 APPROVED 状态仅创建者或 Admin 可见；支持状态、标签过滤。
     *
     * @param projectId 项目 ID
     * @param actor     当前用户 ID
     * @param isAdmin   当前用户是否为项目 Admin（Team Owner 兜底视为 Admin）
     * @param status    状态过滤，可为空（为空即默认 APPROVED）
     * @param tag       标签过滤，可为空
     * @return Memory 列表，按更新时间倒序
     */
    @Select({ "<script>",
            "SELECT * FROM memories WHERE project_id = #{projectId}",
            "AND (status = 'APPROVED' OR created_by = #{actor} OR #{isAdmin})",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='tag != null'>AND JSON_CONTAINS(tags, JSON_QUOTE(#{tag}))</if>",
            "ORDER BY updated_at DESC",
            "</script>" })
    List<MemoryEntity> listMemories(@Param("projectId") UUID projectId, @Param("actor") UUID actor,
            @Param("isAdmin") boolean isAdmin, @Param("status") String status, @Param("tag") String tag);
}
