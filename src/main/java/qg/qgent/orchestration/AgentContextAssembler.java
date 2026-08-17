package qg.qgent.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.dto.GroupContext;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.service.ContextService;

import java.util.UUID;

/**
 * Agent 输入装配器：把任务、步骤、相位、循环反馈与需求群标题/背景、群聊/Skill/Memory 上下文组装为 AgentInput。
 * 上下文由 {@link ContextService#buildForGroup}（后端4）组装并已按用户+项目过滤；
 * 每次 orchestrate 只快照一次（{@link #buildGroupContext}），失败仅告警、不阻断编排——上下文是
 * 需求文本的补充，不替代 task.requirement。
 * <p>
 * token 预算（消息条数动态收敛、截断策略）留待后续与 Skill/Memory 契约对齐后细化，本期固定
 * 50 条近期消息。
 */
@Service
public class AgentContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentContextAssembler.class);

    /**
     * 近期群聊消息条数：ContextService 默认 50、上限 200，固定取默认值避免超 token。
     */
    private static final int DEFAULT_CONTEXT_MESSAGE_LIMIT = 50;

    private final ContextService contextService;

    public AgentContextAssembler(ContextService contextService) {
        this.contextService = contextService;
    }

    /**
     * PLAN 相位输入：无 TaskRun/TaskStep（P0 起 PLAN 为正式 step，主链路走 {@link #assemble}，
     * 本方法保留用于兼容调用）。
     */
    public AgentInput assemblePlan(TaskEntity task) {
        AgentInput input = base(task);
        input.setPhase(OrchestrationPhase.PLAN);
        input.setInstruction("分析需求并制定实现计划");
        applyContext(input, buildGroupContext(task));
        return input;
    }

    /**
     * CODING/TESTING/REVIEWING 相位输入：携带步骤指令、循环反馈、结构化计划、本次修改与测试结果。
     *
     * @param groupContext 本次 orchestrate 快照的群聊/Skill/Memory 上下文；可为 null（组装失败时跳过）。
     */
    public AgentInput assemble(TaskEntity task, TaskStepEntity step, OrchestrationPhase phase,
                               AgentRunOutcome feedback, UUID taskRunId, PlanResult planResult, CodingResult codingResult,
                               TestResult testResult, GroupContext groupContext) {
        AgentInput input = base(task);
        input.setPhase(phase);
        input.setTaskStepId(step.getId());
        input.setTaskRunId(taskRunId);
        input.setInstruction(step.getInstruction());
        input.setFeedback(feedback == null ? null : formatFeedback(feedback));
        input.setPlanResult(planResult);
        input.setCodingResult(codingResult);
        input.setTestResult(testResult);
        applyContext(input, groupContext);
        return input;
    }

    /**
     * 组装群聊/Skill/Memory 上下文：以任务发起人身份（ContextService 内部校验项目成员）拉取
     * requirementGroupId 需求群的近期讨论与项目知识库。每次 orchestrate 调用一次，跨节点复用快照。
     * <p>
     * 组装失败（群不存在、成员校验异常等）仅告警并返回 null——上下文是补充信息，不使任务失败；
     * 后续 Agent 提示词按空上下文渲染。
     */
    public GroupContext buildGroupContext(TaskEntity task) {
        try {
            return contextService.buildForGroup(task.getCreatedBy(), task.getProjectId(),
                    task.getRequirementGroupId(), DEFAULT_CONTEXT_MESSAGE_LIMIT);
        } catch (RuntimeException e) {
            log.warn("context assembly skipped taskId={}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 把快照上下文填入 AgentInput；null 视为组装失败，保持字段为空语义。
     */
    private void applyContext(AgentInput input, GroupContext groupContext) {
        if (groupContext == null) {
            return;
        }
        input.setRequirementTitle(groupContext.getRequirementTitle());
        input.setRequirementDescription(groupContext.getRequirementDescription());
        input.setConversation(groupContext.getConversation());
        input.setSkills(groupContext.getSkills());
        input.setMemories(groupContext.getMemories());
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
