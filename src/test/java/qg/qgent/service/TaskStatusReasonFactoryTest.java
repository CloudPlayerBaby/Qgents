package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.dto.TaskStatusReason;
import qg.qgent.entity.TaskEntity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskStatusReasonFactory} 单测：任务级失败原因的统一组装——
 * 仅失败终态返回、code 按阶段区分、summary 优先持久化 failureReason（白名单去副作用）、
 * 历史数据回退失败 run 原因。
 */
class TaskStatusReasonFactoryTest {

    @Test
    void runningTaskHasNoFailureReason() {
        TaskEntity task = task("RUNNING", "GIT_STORE_FETCH_FAILED", null);
        assertThat(TaskStatusReasonFactory.taskFailure(task, false)).isNull();
    }

    @Test
    void startupFailureWithoutFailedRun() {
        TaskEntity task = task("FAILED", "GIT_STORE_FETCH_FAILED", "代码仓库同步失败");
        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, false);

        assertThat(reason).isNotNull();
        assertThat(reason.getCode()).isEqualTo("STARTUP_FAILED");
        assertThat(reason.getFailureCode()).isEqualTo("GIT_STORE_FETCH_FAILED");
        assertThat(reason.getSummary()).isEqualTo("代码仓库同步失败");
        assertThat(reason.isRetryable()).isTrue();
    }

    @Test
    void executionFailureWithFailedRun() {
        TaskEntity task = task("FAILED", "FILE_PATCH_FAILED", "补丁上下文与文件不一致");
        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, true);

        assertThat(reason).isNotNull();
        assertThat(reason.getCode()).isEqualTo("EXECUTION_FAILED");
        assertThat(reason.getFailureCode()).isEqualTo("FILE_PATCH_FAILED");
        // 稳定码映射受控文案，不回显持久化原文（可能含内部细节）
        assertThat(reason.getSummary()).isEqualTo("补丁无法应用，请重新读取文件后重试");
    }

    @Test
    void gitBranchNotFoundKeepsSanitizedPersistedRepositoryContext() {
        TaskEntity task = task("FAILED", "GIT_BRANCH_NOT_FOUND",
                "仓库 CloudPlayerBaby/test01 不存在基线分支 develop，请在项目仓库配置中选择真实存在的分支后重试");
        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, false);

        assertThat(reason).isNotNull();
        assertThat(reason.getCode()).isEqualTo("STARTUP_FAILED");
        assertThat(reason.getFailureCode()).isEqualTo("GIT_BRANCH_NOT_FOUND");
        assertThat(reason.getSummary())
                .contains("CloudPlayerBaby/test01")
                .contains("develop")
                .contains("基线分支");
    }

    @Test
    void stableCodeWithInternalDetailIsRedacted() {
        // 安全护栏：稳定码 + 含内部细节的持久化原文 → 只回显受控文案，绝不泄漏内部细节。
        TaskEntity task = task("FAILED", "CODING_NO_ACTUAL_CHANGE",
                "coding agent failed: CODING_NO_ACTUAL_CHANGE: internal model details");
        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, true);

        assertThat(reason).isNotNull();
        assertThat(reason.getFailureCode()).isEqualTo("CODING_NO_ACTUAL_CHANGE");
        assertThat(reason.getSummary()).isEqualTo("代码步骤未产生实际文件变更");
        assertThat(reason.getSummary()).doesNotContain("internal model details");
    }

    @Test
    void deliveryFailureMapsToDeliveryCode() {
        TaskEntity task = task("DELIVERY_FAILED", "TASK_FINALIZATION_DIFF", "最终 Diff 生成失败");
        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, true);

        assertThat(reason).isNotNull();
        assertThat(reason.getCode()).isEqualTo("DELIVERY_FAILED");
        assertThat(reason.getSummary()).isEqualTo("最终 Diff 生成失败");
    }

    @Test
    void historicalTaskWithoutTaskLevelCodeFallsBackToFailedRunReason() {
        // 历史数据：任务级 failureCode 为空但有失败 run → 回退 run 的原因。
        TaskEntity task = task("FAILED", null, null);
        TaskStatusReason runReason = new TaskStatusReason("EXECUTION_FAILED", "FILE_PATCH_FAILED",
                "执行失败", "补丁上下文与文件不一致", true, null);

        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, true, runReason);

        assertThat(reason).isNotNull();
        assertThat(reason.getFailureCode()).isEqualTo("FILE_PATCH_FAILED");
        assertThat(reason.getSummary()).isEqualTo("补丁上下文与文件不一致");
    }

    @Test
    void qualityLoopsExhaustedIsNotDeliveryFailure() {
        TaskEntity task = task("FAILED", "TASK_QUALITY_LOOPS_EXHAUSTED", "任务多次未通过质量验证，修复循环已耗尽");
        TaskStatusReason reason = TaskStatusReasonFactory.taskFailure(task, true);

        assertThat(reason).isNotNull();
        assertThat(reason.getCode()).isEqualTo("EXECUTION_FAILED");
        assertThat(reason.getFailureCode()).isNull(); // 未进白名单，但 summary 保留真实原因
        assertThat(reason.getSummary()).isEqualTo("任务多次未通过质量验证，修复循环已耗尽");
    }

    @Test
    void stableQualityCodesMapToControlledText() {
        // TEST_COMMAND_NOT_FOUND：项目未配置测试命令，稳定码公开，summary 用受控文案而非模型原文。
        TaskEntity noTest = task("FAILED", "TEST_COMMAND_NOT_FOUND", "模型内部细节不应外泄");
        TaskStatusReason noTestReason = TaskStatusReasonFactory.taskFailure(noTest, true);

        assertThat(noTestReason).isNotNull();
        assertThat(noTestReason.getFailureCode()).isEqualTo("TEST_COMMAND_NOT_FOUND");
        assertThat(noTestReason.getSummary()).contains("未检测到受支持的项目/测试命令");
        assertThat(noTestReason.getSummary()).doesNotContain("模型内部细节");

        // REVIEW_ASSERTION_TARGET_NOT_FOUND：审查验收目标缺失，稳定码公开且可重试。
        TaskEntity missingTarget = task("FAILED", "REVIEW_ASSERTION_TARGET_NOT_FOUND", "不应回显的模型原文");
        TaskStatusReason missingTargetReason = TaskStatusReasonFactory.taskFailure(missingTarget, true);

        assertThat(missingTargetReason).isNotNull();
        assertThat(missingTargetReason.getFailureCode()).isEqualTo("REVIEW_ASSERTION_TARGET_NOT_FOUND");
        assertThat(missingTargetReason.getSummary()).contains("验收目标");
        assertThat(missingTargetReason.getSummary()).doesNotContain("模型原文");
        assertThat(missingTargetReason.isRetryable()).isTrue();
    }

    private TaskEntity task(String status, String failureCode, String failureReason) {
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(UUID.randomUUID());
        task.setStatus(status);
        task.setFailureCode(failureCode);
        task.setFailureReason(failureReason);
        task.setFailureRetryable(true);
        task.setFailureOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        return task;
    }
}
