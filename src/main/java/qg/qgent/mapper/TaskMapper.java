package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.TaskEntity;

import java.util.UUID;

/**
 * Data access for single-key tasks.
 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    /**
     * 原子领取尚未开始的 Task，防止同一 Task 被多个编排器并发执行。
     */
    @Update("update tasks set status='RUNNING',updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{taskId} and project_id=#{projectId} and status in ('PLANNING','PENDING')")
    int claimForOrchestration(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /**
     * Locks one Task while changing execution or continuation state.
     */
    @Select("select * from tasks where id=#{taskId} for update")
    TaskEntity selectByIdForUpdate(UUID taskId);

    /**
     * 返回项目内当前最大的 display_code 数字序号（如 T-1024 返回 1024）；无任务时返回 null。
     * 调用方须在持有项目级锁的事务内使用，保证序号单调递增且不重复。
     */
    @Select("SELECT MAX(CAST(SUBSTRING_INDEX(display_code, '-', -1) AS UNSIGNED)) "
            + "FROM tasks WHERE project_id = #{projectId}")
    Long selectMaxDisplayCodeSeq(@Param("projectId") UUID projectId);
}
