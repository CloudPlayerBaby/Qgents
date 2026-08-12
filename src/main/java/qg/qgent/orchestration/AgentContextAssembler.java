package qg.qgent.orchestration;

import org.springframework.stereotype.Service;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.UUID;

/**
 * Agent 输入装配器：把任务、步骤、相位与循环反馈组装为 AgentInput。
 * Phase 1 保持精简（不含聊天历史、Skill/Memory、代码快照），
 * 完整上下文装配（含 token 预算）留待 Phase 2 与后端4 的 Skill/Memory 契约对齐后实现。
 */
@Service
public class AgentContextAssembler {

    /** PLAN 相位输入：无 TaskRun/TaskStep。 */
    public AgentInput assemblePlan(TaskEntity task) {
        AgentInput input = base(task);
        input.setPhase(OrchestrationPhase.PLAN);
        input.setInstruction("分析需求并制定实现计划");
        return input;
    }

    /** CODING/TESTING/REVIEWING 相位输入：携带步骤指令、循环反馈、结构化计划、本次修改与测试结果。 */
    public AgentInput assemble(TaskEntity task, TaskStepEntity step, OrchestrationPhase phase,
            AgentRunOutcome feedback, UUID taskRunId, PlanResult planResult, CodingResult codingResult,
            TestResult testResult) {
        AgentInput input = base(task);
        input.setPhase(phase);
        input.setTaskStepId(step.getId());
        input.setTaskRunId(taskRunId);
        input.setInstruction(step.getInstruction());
        input.setFeedback(feedback == null ? null : formatFeedback(feedback));
        input.setPlanResult(planResult);
        input.setCodingResult(codingResult);
        input.setTestResult(testResult);
        return input;
    }

    private AgentInput base(TaskEntity task) {
        AgentInput input = new AgentInput();
        input.setProjectId(task.getProjectId());
        input.setTaskId(task.getId());
        input.setTaskTitle(task.getTitle());
        input.setRequirement(task.getRequirement());
        input.setWorkspaceSummary("workspace:" + task.getWorkspaceId());
        input.setWorkspaceId(task.getWorkspaceId());
        return input;
    }

    private String formatFeedback(AgentRunOutcome feedback) {
        TestResult test = feedback.getTestResult();
        if (test != null && !test.getFailures().isEmpty()) {
            return "前一轮测试失败：" + test.getFailures();
        }
        ReviewResult review = feedback.getReviewResult();
        if (review != null && !review.getFindings().isEmpty()) {
            StringBuilder sb = new StringBuilder("前一轮审查问题：").append(review.getFindings());
            if (review.getSuggestions() != null && !review.getSuggestions().isEmpty()) {
                sb.append("\n审查建议：").append(review.getSuggestions());
            }
            return sb.toString();
        }
        return feedback.getMessage();
    }
}
