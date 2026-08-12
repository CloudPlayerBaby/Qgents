package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.TaskStepMapper;

import java.util.UUID;

/**
 * 相位 → TaskStep 选择器：按相位对应的角色从任务步骤中选出待执行步骤。
 * Phase 1 约定每个角色只有一个步骤（Plan 产出的 DEVELOPER/TESTER/REVIEWER），
 * 依赖 DAG 就绪校验与更细粒度调度留待 Phase 2。
 */
@Service
public class StepScheduler {
    private final TaskStepMapper stepMapper;

    public StepScheduler(TaskStepMapper stepMapper) {
        this.stepMapper = stepMapper;
    }

    /** 返回任务中与相位角色匹配的步骤；找不到时抛 IllegalStateException。 */
    public TaskStepEntity findStepForPhase(UUID taskId, OrchestrationPhase phase) {
        String role = phase.role();
        if (role == null) {
            throw new IllegalStateException("Phase " + phase + " has no task step to run");
        }
        return stepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId)
                .eq(TaskStepEntity::getRole, role)
                .orderByAsc(TaskStepEntity::getSequenceNo))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + role + " step for task " + taskId));
    }
}
