package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.TaskStepEntity;

import java.util.UUID;

/**
 * Data access for single-key workflow steps.
 */
@Mapper
public interface TaskStepMapper extends BaseMapper<TaskStepEntity> {
    /**
     * 返回任务中第一个尚未完成（非 SUCCEEDED/SKIPPED/CANCELLED）的步骤 ID；全部完成时返回 null。
     * 供恢复调度器从断点续跑使用。
     */
    @Select("select id from task_steps where task_id=#{taskId} "
            + "and status not in ('SUCCEEDED','SKIPPED','CANCELLED') "
            + "order by sequence_no limit 1")
    UUID selectFirstIncompleteStep(@Param("taskId") UUID taskId);

    /**
     * 返回任务中 sequenceNo 最小的步骤 ID（全量重跑入口）；无步骤时返回 null。
     */
    @Select("select id from task_steps where task_id=#{taskId} order by sequence_no limit 1")
    UUID selectFirstStep(@Param("taskId") UUID taskId);

    /**
     * 在计划物化事务中锁定任务的全部步骤，防止并发物化写出两套执行清单。
     */
    @Select("select * from task_steps where task_id=#{taskId} order by sequence_no for update")
    java.util.List<TaskStepEntity> selectByTaskForUpdate(@Param("taskId") UUID taskId);
}
