package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.QualityCheckResultEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.QualityCheckResultMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * PR 创建成功后的质量门禁自动写入（MR_FIRST 交付链路的一环）：
 * <ul>
 *   <li><b>AI_REVIEW</b>：读取 Task 的 REVIEWING 执行产物（脱敏结构化 review 结果），
 *       按 {@code review.success} 映射 PASSED/FAILED。产物已由编排侧截断脱敏，可直接消费；
 *       无 REVIEWING 产物（异常路径）不写检查（不伪造），记 warn。</li>
 *   <li><b>MR_PENDING 通知</b>：PR 创建成功后通知任务发起人（kind 复用 §7.1，resourceId=mrId）。</li>
 * </ul>
 * MR 创建阶段会由 {@link MergeRequestService} 对已核验的预检 Dry Run 与 CQ+1 事实进行投影；
 * 本类仅补充创建后的 AI_REVIEW 与通知，与该预检投影互补。TESTSET 仍由 MR 前 Dry Run 的真实执行
 * 强制校验，不伪造独立 MR 级检查。所有检查写入均在短事务内完成，且不包裹网络调用。
 */
@Service
@Slf4j
public class MrQualityGateService {
    private final MergeRequestMapper mergeRequests;
    private final TaskExecutionArtifactMapper artifacts;
    private final TaskMapper tasks;
    private final NotificationService notifications;
    private final QualityCheckResultMapper qualityChecks;
    private final TransactionTemplate transactions;

    public MrQualityGateService(MergeRequestMapper mergeRequests, TaskExecutionArtifactMapper artifacts,
                                TaskMapper tasks, NotificationService notifications,
                                QualityCheckResultMapper qualityChecks, TransactionTemplate transactions) {
        this.mergeRequests = mergeRequests;
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.notifications = notifications;
        this.qualityChecks = qualityChecks;
        this.transactions = transactions;
    }

    /**
     * PR 创建成功后调用（事务外、网络调用之后）：写入 AI_REVIEW 检查并发 MR_PENDING 通知。
     * 每一步独立 try/catch——检查写入失败不影响已创建的 PR 事实，也不互相阻塞；
     * 失败事实记日志，可由 MR sync / 手动操作补偿。
     */
    public void onPullRequestCreated(MergeRequestEntity mr) {
        if (mr == null || mr.getTaskId() == null) {
            return;
        }
        try {
            writeAiReviewCheck(mr);
        } catch (RuntimeException failure) {
            log.warn("AI_REVIEW check write failed mrId={} taskId={}: {}", mr.getId(), mr.getTaskId(),
                    failure.getMessage());
        }
        try {
            notifyMrPending(mr);
        } catch (RuntimeException failure) {
            log.warn("MR_PENDING notification failed mrId={} taskId={}: {}", mr.getId(), mr.getTaskId(),
                    failure.getMessage());
        }
    }

    /**
     * AI_REVIEW：读取任务最新 REVIEWING 产物的 review 键映射为检查结果。
     * 产物缺失（编排异常路径）不写检查——不伪造 PASSED/FAILED 之外的任何状态。
     */
    private void writeAiReviewCheck(MergeRequestEntity mr) {
        TaskExecutionArtifactEntity artifact = artifacts.selectOne(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                .eq(TaskExecutionArtifactEntity::getTaskId, mr.getTaskId())
                .eq(TaskExecutionArtifactEntity::getArtifactType, "REVIEWING")
                .orderByDesc(TaskExecutionArtifactEntity::getSequenceNo)
                .last("LIMIT 1"));
        if (artifact == null || !(artifact.getSummary() instanceof Map<?, ?> summary)
                || !(summary.get("review") instanceof Map<?, ?> review)) {
            log.warn("AI_REVIEW check skipped, no REVIEWING artifact, mrId={} taskId={}", mr.getId(), mr.getTaskId());
            return;
        }
        boolean success = Boolean.TRUE.equals(review.get("success"));
        String reason = review.get("summary") == null ? null : String.valueOf(review.get("summary"));
        writeCheck(mr, "AI_REVIEW", success ? "PASSED" : "FAILED", "ARTIFACT",
                success ? reason : "AI 审查未通过：" + reason);
    }

    /**
     * 通知任务发起人：MR 已创建待审查（MR_FIRST 场景替代 DELIVERABLE_PENDING 的交付语义）。
     */
    private void notifyMrPending(MergeRequestEntity mr) {
        TaskEntity task = tasks.selectById(mr.getTaskId());
        if (task == null || notifications == null) {
            return;
        }
        notifications.notify(task.getCreatedBy(), task.getProjectId(), task.getRequirementGroupId(),
                "MR_PENDING", "MR 待审查：" + mr.getTitle(),
                "任务「" + task.getTitle() + "」的 PR 已创建，请查看质量门禁并完成审查",
                mr.getId().toString());
    }

    /**
     * 与 MergeRequestService.writeCheck 同构的独立写入（避免循环依赖），attemptNo 在
     * 同提交同类型内递增，重复触发时产生新 attempt 而非重复行。
     */
    private void writeCheck(MergeRequestEntity mr, String checkType, String status, String source, String reason) {
        inTransaction(() -> {
            MergeRequestEntity locked = mergeRequests.selectByIdForUpdate(mr.getId());
            if (locked == null) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            QualityCheckResultEntity check = new QualityCheckResultEntity();
            check.setId(UuidV7.next());
            check.setMergeRequestId(locked.getId());
            check.setCheckType(checkType);
            check.setAttemptNo(nextAttemptNo(locked.getId(), checkType, locked.getHeadCommit()));
            check.setStatus(status);
            check.setCommitSha(locked.getHeadCommit());
            check.setSource(source);
            check.setSummary(reason == null ? Map.of() : Map.of("reason", reason));
            check.setStartedAt(now);
            check.setCompletedAt(now);
            check.setCreatedAt(now);
            qualityChecks.insert(check);
            log.info("quality check written mrId={} checkType={} status={} attemptNo={} source={}",
                    locked.getId(), checkType, status, check.getAttemptNo(), source);
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }

    private int nextAttemptNo(UUID mrId, String checkType, String commitSha) {
        QualityCheckResultEntity last = qualityChecks.selectOne(
                Wrappers.<QualityCheckResultEntity>lambdaQuery()
                        .eq(QualityCheckResultEntity::getMergeRequestId, mrId)
                        .eq(QualityCheckResultEntity::getCheckType, checkType)
                        .eq(QualityCheckResultEntity::getCommitSha, commitSha)
                        .orderByDesc(QualityCheckResultEntity::getAttemptNo)
                        .last("LIMIT 1"));
        return last == null ? 1 : last.getAttemptNo() + 1;
    }
}
