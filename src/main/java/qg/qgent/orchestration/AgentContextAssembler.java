package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.GroupContext;
import qg.qgent.dto.ContextRepository;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.orchestration.agent.ReviewVerdictComputer;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.service.ContextService;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.entity.WorkspaceRepositoryEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 输入装配器：把任务、步骤、相位、循环反馈与需求群标题/背景、群聊/Skill/Memory 上下文组装为 AgentInput。
 * 上下文由 {@link ContextService#buildForGroup}（后端4）组装并已按用户+项目过滤；
 * 新建 Task 在创建时持久化默认上下文快照，编排时通过 {@link #buildGroupContext} 复用；仅迁移前
 * 历史 Task 缺少快照时才兼容实时读取。上下文是需求文本的补充，不替代 task.requirement。
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
    private final TaskContextSnapshotCodec contextSnapshotCodec;
    private final DiffReviewBatchMapper diffBatches;
    private final DiffMapper diffMapper;
    private final WorkspaceRepositoryMapper workspaceRepositories;
    /**
     * Review finding 严重度归一化（风格降级）复用宽松判定器，保证"只回灌 BLOCKER/MAJOR"与
     * {@link ReviewVerdictComputer} 的通过判定完全同源：风格类 MAJOR 被降级后不再喂给 Coding。
     */
    private final ReviewVerdictComputer verdictComputer = new ReviewVerdictComputer();

    public AgentContextAssembler(ContextService contextService, TaskContextSnapshotCodec contextSnapshotCodec,
                                 DiffReviewBatchMapper diffBatches, DiffMapper diffMapper) {
        this(contextService, contextSnapshotCodec, diffBatches, diffMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentContextAssembler(ContextService contextService, TaskContextSnapshotCodec contextSnapshotCodec,
                                 DiffReviewBatchMapper diffBatches, DiffMapper diffMapper,
                                 WorkspaceRepositoryMapper workspaceRepositories) {
        this.contextService = contextService;
        this.contextSnapshotCodec = contextSnapshotCodec;
        this.diffBatches = diffBatches;
        this.diffMapper = diffMapper;
        this.workspaceRepositories = workspaceRepositories;
    }

    /**
     * PLAN bootstrap 输入：无 TaskRun/TaskStep。本方法保留供无步骤上下文的调用方使用。
     */
    public AgentInput assemblePlan(TaskEntity task) {
        AgentInput input = base(task);
        input.setPhase(OrchestrationPhase.PLAN);
        input.setInstruction("分析需求并制定实现计划");
        applyContext(input, task, buildGroupContext(task));
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
        return assemble(task, step, phase, feedback, taskRunId, planResult, codingResult, testResult, groupContext, null);
    }

    public AgentInput assemble(TaskEntity task, TaskStepEntity step, OrchestrationPhase phase,
                               AgentRunOutcome feedback, UUID taskRunId, PlanResult planResult, CodingResult codingResult,
                               TestResult testResult, GroupContext groupContext,
                               Map<String, Integer> inheritedPatchFailureCounts) {
        AgentInput input = base(task);
        input.setPhase(phase);
        input.setTaskStepId(step.getId());
        input.setTaskRunId(taskRunId);
        input.setInstruction(step.getInstruction());
        input.setExecutionMode(step.getExecutionMode());
        input.setAllowedPaths(step.getAllowedPaths());
        input.setTargetFiles(step.getTargetFiles());
        input.setFeedback(feedback == null ? null : formatFeedback(feedback, phase));
        input.setRetryContext(retryContext(feedback, inheritedPatchFailureCounts));
        input.setPlanResult(planResult);
        input.setCodingResult(codingResult);
        input.setTestResult(testResult);
        applyContext(input, task, groupContext);
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
        if (task.getContextSnapshot() != null) {
            try {
                GroupContext snapshot = contextSnapshotCodec.decode(task.getContextSnapshot());
                if (snapshot != null) {
                    return snapshot;
                }
                log.warn("context snapshot invalid taskId={}, no live fallback will overwrite it", task.getId());
                return null;
            } catch (RuntimeException e) {
                log.warn("context snapshot decode failed taskId={}, no live fallback will overwrite it", task.getId());
                return null;
            }
        }
        // 迁移前历史任务没有快照；明确保留实时读取兼容路径。新建 Task 必须已有 contextSnapshot，
        // 因而其重试和恢复不会读取后续群消息或 Memory。
        log.warn("legacy task without context snapshot taskId={}, using live context compatibility fallback", task.getId());
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
    private void applyContext(AgentInput input, TaskEntity task, GroupContext groupContext) {
        if (groupContext != null) {
            input.setRequirementTitle(groupContext.getRequirementTitle());
            input.setRequirementDescription(groupContext.getRequirementDescription());
            input.setConversation(groupContext.getConversation());
            input.setSkills(groupContext.getSkills());
            input.setMemories(groupContext.getMemories());
            input.setRepositories(enrichRepositories(task, groupContext.getRepositories()));
        }
        // 续作任务：把源 Task 的正式 Diff 摘要显式注入对话头，让 Agent 明确本轮是基于哪一版 Diff 增量修改；
        // 顺带补全群上下文缺失（buildForGroup 失效）时仍可见源 Diff 来源。非续作任务保持原列表不变。
        input.setConversation(attachContinuationDiff(task, input.getConversation()));
    }

    /** 为群仓库清单补充当前 Task 的 repo 别名、基线和 feature branch。 */
    private List<ContextRepository> enrichRepositories(TaskEntity task, List<ContextRepository> repositories) {
        if (repositories == null || repositories.isEmpty() || workspaceRepositories == null
                || task.getWorkspaceId() == null) {
            return repositories;
        }
        List<WorkspaceRepositoryEntity> worktrees = workspaceRepositories.selectByWorkspace(task.getWorkspaceId());
        if (worktrees == null || worktrees.isEmpty()) {
            return repositories;
        }
        java.util.Map<String, WorkspaceRepositoryEntity> byRepository = worktrees.stream()
                .collect(java.util.stream.Collectors.toMap(
                        worktree -> String.valueOf(worktree.getProjectRepositoryId()),
                        java.util.function.Function.identity(), (first, ignored) -> first));
        return repositories.stream().map(repository -> {
            WorkspaceRepositoryEntity worktree = byRepository.get(repository.getRepositoryId());
            if (worktree == null) {
                return repository;
            }
            return new ContextRepository(repository.getRepositoryId(), repository.getName(), repository.getFullName(),
                    repository.getDefaultBranch(), worktree.getWorkspacePath(), worktree.getBaseRef(),
                    worktree.getSourceBranch());
        }).toList();
    }

    /**
     * 续作任务（continuationOfTaskId 非空）时，在对话头追加一条描述源 Task 正式 Diff 的
     * {@link ContextMessage}（含 diffId 与增删行统计），供 Agent 定位本轮增量修改的基线。
     * 源批次或 Diff 不存在时原样返回；非续作任务不改变对话。
     */
    private List<ContextMessage> attachContinuationDiff(TaskEntity task, List<ContextMessage> conversation) {
        UUID sourceTaskId = task.getContinuationOfTaskId();
        if (sourceTaskId == null) {
            return conversation;
        }
        ContextMessage sourceDiff = buildContinuationDiffMessage(task.getProjectId(), sourceTaskId);
        if (sourceDiff == null) {
            return conversation;
        }
        List<ContextMessage> result = new ArrayList<>();
        result.add(sourceDiff);
        if (conversation != null && !conversation.isEmpty()) {
            result.addAll(conversation);
        }
        return result;
    }

    /**
     * 组装源 Task 最近一次正式 Diff 批次的摘要消息；无批次或无 Diff 时返回 null。
     */
    private ContextMessage buildContinuationDiffMessage(UUID projectId, UUID sourceTaskId) {
        DiffReviewBatchEntity batch = latestBatch(projectId, sourceTaskId);
        if (batch == null) {
            return null;
        }
        List<DiffEntity> diffs = diffMapper.selectList(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getReviewBatchId, batch.getId())
                .orderByAsc(DiffEntity::getProjectRepositoryId));
        if (diffs.isEmpty()) {
            return null;
        }
        List<String> diffIds = diffs.stream().map(d -> d.getId().toString()).toList();
        int additions = diffs.stream().mapToInt(this::additions).sum();
        int deletions = diffs.stream().mapToInt(this::deletions).sum();
        String text = "本任务是源 Task 正式 Diff 的增量修改，基线 Diff：" + diffIds + "，变更统计：新增 "
                + additions + " 行 / 删除 " + deletions + " 行";
        return new ContextMessage(0L, "DIFF", "AGENT", null, text);
    }

    private int additions(DiffEntity diff) {
        return stat(diff, "additions");
    }

    private int deletions(DiffEntity diff) {
        return stat(diff, "deletions");
    }

    private int stat(DiffEntity diff, String key) {
        if (diff.getChangeStats() == null) {
            return 0;
        }
        Object value = diff.getChangeStats().get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 源 Task 最近一次创建的 Diff 批次（与任务中心详情同序：按创建时间倒序取首条）。
     */
    private DiffReviewBatchEntity latestBatch(UUID projectId, UUID taskId) {
        List<DiffReviewBatchEntity> list = diffBatches.selectList(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getProjectId, projectId)
                .eq(DiffReviewBatchEntity::getTaskId, taskId)
                .orderByDesc(DiffReviewBatchEntity::getCreatedAt).last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private AgentInput base(TaskEntity task) {
        AgentInput input = new AgentInput();
        input.setProjectId(task.getProjectId());
        input.setActorId(task.getCreatedBy());
        input.setRequirementGroupId(task.getRequirementGroupId());
        input.setTaskId(task.getId());
        input.setTaskTitle(task.getTitle());
        input.setRequirement(task.getRequirement());
        input.setWorkspaceSummary("workspace:" + task.getWorkspaceId());
        input.setWorkspaceId(task.getWorkspaceId());
        return input;
    }

    /**
     * 把上一轮失败反馈渲染成 Coding/Review 可读文本。相位感知过滤：CODING 的修复反馈只保留
     * 归一化后 BLOCKER/MAJOR 的 review finding（宽松化降级掉的风格项不喂给 Coding，避免诱导
     * 模型顺手修风格）并去掉 suggestions；REVIEWING 的复核反馈保留全部 findings + suggestions，
     * 供 Review 复核旧 finding。与 {@link #retryContext} 共用 {@link #actionableFailures}，保证
     * "前一轮反馈"与"重试上下文 failures"内容一致，同一批失败项不再重复渲染多遍。
     */
    private String formatFeedback(AgentRunOutcome feedback, OrchestrationPhase phase) {
        if (feedback == null) {
            return "";
        }
        if (feedback.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE) {
            String code = ExecutionContentSanitizer.stableInfrastructureCode(feedback.getFailureCode());
            return "前一轮基础设施失败（" + code + "）："
                    + ExecutionContentSanitizer.infrastructureDescription(code);
        }
        List<String> failures = actionableFailures(feedback, phase);
        if (!failures.isEmpty()) {
            TestResult test = feedback.getTestResult();
            if (test != null && test.getFailures() != null && !test.getFailures().isEmpty()) {
                return "前一轮测试失败：" + failures;
            }
            ReviewResult review = feedback.getReviewResult();
            if (review != null && review.getFindings() != null && !review.getFindings().isEmpty()) {
                StringBuilder sb = new StringBuilder("前一轮审查问题：").append(failures);
                if (phase != OrchestrationPhase.CODING && review.getSuggestions() != null
                        && !review.getSuggestions().isEmpty()) {
                    sb.append("\n审查建议：").append(review.getSuggestions());
                }
                return sb.toString();
            }
        }
        return feedback.getMessage();
    }

    /**
     * 上一轮失败的可修复项列表（单条已截断脱敏），供 feedback 文本与 retryContext.failures 共用，
     * 保证两者内容一致。Test 失败项全部视为可修复；Review finding 仅当修复反馈（CODING）时保留
     * 归一化后 BLOCKER/MAJOR（风格降级项与 MINOR/INFO 不参与修复反馈），复核反馈（非 CODING）
     * 保留全部。
     */
    private List<String> actionableFailures(AgentRunOutcome feedback, OrchestrationPhase phase) {
        if (feedback == null) {
            return List.of();
        }
        TestResult test = feedback.getTestResult();
        if (test != null && test.getFailures() != null && !test.getFailures().isEmpty()) {
            return test.getFailures().stream()
                    .map(failure -> limit(String.valueOf(failure.getName()) + ": " + String.valueOf(failure.getReason()),
                            500))
                    .toList();
        }
        ReviewResult review = feedback.getReviewResult();
        if (review != null && review.getFindings() != null && !review.getFindings().isEmpty()) {
            List<ReviewResult.Finding> effective = phase == OrchestrationPhase.CODING
                    ? verdictComputer.compute(review.getFindings()).normalizedFindings().stream()
                            .filter(finding -> isBlockerOrMajor(finding.getSeverity()))
                            .toList()
                    : review.getFindings();
            return effective.stream()
                    .map(finding -> limit(String.valueOf(finding.getSeverity()) + " " + String.valueOf(finding.getFile())
                            + ": " + String.valueOf(finding.getIssue()), 500))
                    .toList();
        }
        return List.of();
    }

    private boolean isBlockerOrMajor(String severity) {
        String effective = severity == null ? "" : severity.toUpperCase(Locale.ROOT);
        return "BLOCKER".equals(effective) || "MAJOR".equals(effective);
    }

    /**
     * retryContext 的受控失败概述：基础设施失败走稳定码说明；质量失败只给一行计数概述，
     * 明细由 {@link #actionableFailures} 提供的 failures 承载，不再整份 dump 到 failureSummary。
     */
    private String failureSummary(AgentRunOutcome outcome) {
        if (outcome == null) {
            return "";
        }
        if (outcome.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE) {
            String code = ExecutionContentSanitizer.stableInfrastructureCode(outcome.getFailureCode());
            return "前一轮基础设施失败（" + code + "）："
                    + ExecutionContentSanitizer.infrastructureDescription(code);
        }
        List<String> failures = actionableFailures(outcome, OrchestrationPhase.CODING);
        if (!failures.isEmpty()) {
            TestResult test = outcome.getTestResult();
            if (test != null && test.getFailures() != null && !test.getFailures().isEmpty()) {
                return "前一轮测试失败，共 " + failures.size() + " 项，详见 failures";
            }
            return "前一轮审查未通过，共 " + failures.size() + " 个待修复问题，详见 failures";
        }
        String message = outcome.getMessage();
        return message == null ? "前一轮执行未通过" : message;
    }

    private RetryContext retryContext(AgentRunOutcome outcome, Map<String, Integer> inheritedCounts) {
        if (outcome == null && (inheritedCounts == null || inheritedCounts.isEmpty())) {
            return null;
        }
        RetryContext context = new RetryContext();
        String code = outcome == null ? "FILE_PATCH_FAILED" : outcome.getFailureCode();
        if (code == null || code.isBlank()) {
            code = outcome.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE ? "INFRASTRUCTURE_FAILURE"
                    : outcome.getOutcome() == RunOutcome.FAILED_QUALITY ? "QUALITY_GATE_FAILED" : "AGENT_RETRY";
        }
        context.setFailureCode(limit(code, 128));
        // failureSummary 只给一行受控概述，明细走 failures、正文走 feedback，同一批失败项
        // 在 Coding prompt 里只出现两次（正文 + 结构化列表），不再整份 dump 三遍。
        context.setFailureSummary(limit(outcome == null
                ? "前序运行的补丁连续失败，请先重新 read_file，再按要求切换 replace_file"
                : failureSummary(outcome), 2000));
        List<String> failures = actionableFailures(outcome, OrchestrationPhase.CODING);
        if (!failures.isEmpty()) {
            context.setFailures(failures.stream().limit(20).toList());
        }
        CodingResult coding = outcome == null ? null : outcome.getCodingResult();
        if (coding != null && coding.getModifiedFiles() != null) {
            context.setModifiedFiles(coding.getModifiedFiles().stream().map(value -> limit(value, 300)).limit(100).toList());
        }
        Map<String, Integer> counts = outcome == null ? null : outcome.getPatchFailureCounts();
        if (counts == null || counts.isEmpty()) {
            counts = inheritedCounts;
        }
        if (counts != null) {
            context.setPatchFailureCounts(counts.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                    .limit(100)
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (left, right) -> right, java.util.LinkedHashMap::new)));
        }
        context.setInstruction(outcome != null && outcome.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE
                ? "先检查并恢复基础设施，再在相同上下文重试；不要修改业务代码绕过门禁。"
                : "根据失败项修复代码并重新执行验证，不得绕过质量门禁。");
        return context;
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        String sanitized = ExecutionContentSanitizer.sanitize(value);
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }

}
