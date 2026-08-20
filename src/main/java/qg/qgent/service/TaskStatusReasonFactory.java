package qg.qgent.service;

import qg.qgent.dto.TaskStatusReason;
import qg.qgent.entity.TaskEntity;
import qg.qgent.orchestration.ExecutionContentSanitizer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 任务级失败原因的统一组装入口。
 * <p>
 * 任务详情接口（{@link TaskDisplayService}）与诊断接口（{@link TaskRunService}）共用本工厂，
 * 保证同一任务在两条链路返回一致的 {@code failureCode/title/summary/retryable}，避免前端
 * 同一页面出现互相矛盾的失败横幅。
 * <p>
 * 语义约定：
 * <ul>
 *   <li>仅任务处于失败终态（FAILED/DELIVERY_FAILED）时返回非 null；</li>
 *   <li>{@code summary} 优先使用启动/执行时持久化的 {@code failureReason}（含仓库/分支等上下文），
 *       未持久化时才回退到 {@code ExecutionContentSanitizer.userFailureDescription} 的通用文案；
 *       ——这样白名单未定义的内部码也不会把真实原因降级成「任务执行失败，请查看执行记录」；</li>
 *   <li>{@code code} 按失败阶段区分：交付失败 → DELIVERY_FAILED，存在失败 TaskRun → EXECUTION_FAILED，
 *       否则（尚未创建 TaskRun 即失败）→ STARTUP_FAILED。</li>
 *   <li>任务级 {@code failureCode} 为空（历史数据）时，回退最近失败 TaskRun 的原因（fallback）。</li>
 * </ul>
 */
public final class TaskStatusReasonFactory {

    private TaskStatusReasonFactory() {
    }

    /**
     * 任务级失败原因；任务不在失败终态或无失败信息时返回 null。
     *
     * @param task           任务（可含持久化的 failureCode/failureReason）
     * @param hasFailedRun   是否存在已落 FAILED 的 TaskRun（区分执行失败与启动失败）
     * @return 失败原因摘要；非失败终态返回 null
     */
    public static TaskStatusReason taskFailure(TaskEntity task, boolean hasFailedRun) {
        return taskFailure(task, hasFailedRun, null);
    }

    /**
     * 任务级失败原因；任务不在失败终态或无失败信息时返回 null。
     *
     * @param task           任务（可含持久化的 failureCode/failureReason）
     * @param hasFailedRun   是否存在已落 FAILED 的 TaskRun（区分执行失败与启动失败）
     * @param failedRunReason 最近失败 TaskRun 的原因；任务级 failureCode 为空时作为回退
     * @return 失败原因摘要；非失败终态返回 null
     */
    public static TaskStatusReason taskFailure(TaskEntity task, boolean hasFailedRun,
                                               TaskStatusReason failedRunReason) {
        if (task == null) {
            return null;
        }
        boolean terminalFailure = "FAILED".equals(task.getStatus()) || "DELIVERY_FAILED".equals(task.getStatus());
        if (!terminalFailure) {
            return null;
        }
        boolean taskLevelCode = task.getFailureCode() != null && !task.getFailureCode().isBlank();
        if (!taskLevelCode) {
            // 历史数据未持久化任务级失败码：回退最近失败 TaskRun 的原因；仍无则按执行失败兜底。
            if (failedRunReason != null) {
                return failedRunReason;
            }
            if (!hasFailedRun) {
                return null;
            }
            return new TaskStatusReason("EXECUTION_FAILED", "EXECUTION_FAILED", "任务执行失败",
                    "任务执行失败，可查看失败运行", true, iso(task.getUpdatedAt()));
        }
        String failureCode = ExecutionContentSanitizer.publicFailureCode(task.getFailureCode());
        boolean delivery = isDeliveryFailure(task.getFailureCode());
        String code = delivery ? "DELIVERY_FAILED"
                : hasFailedRun ? "EXECUTION_FAILED" : "STARTUP_FAILED";
        // summary 优先使用持久化 failureReason（含仓库/分支等上下文）；持久化值一律先脱敏，
        // 防止旧数据或未脱敏写入把模型原文/内部细节泄漏给前端。
        String summary = task.getFailureReason() != null && !task.getFailureReason().isBlank()
                ? sanitizedReason(task.getFailureReason(), failureCode)
                : ExecutionContentSanitizer.userFailureDescription(failureCode);
        String title = delivery ? "任务交付失败" : "任务执行失败";
        boolean retryable = !Boolean.FALSE.equals(task.getFailureRetryable());
        return new TaskStatusReason(code, failureCode, title, summary, retryable,
                iso(task.getFailureOccurredAt()));
    }

    /**
     * 持久化失败原因的脱敏展示：
     * <ul>
     *   <li>稳定码可映射受控文案且不需要具体上下文（如 CODING_NO_ACTUAL_CHANGE）→ 直接用受控文案，
     *       绝不回显持久化原文（可能含模型内部细节）；</li>
     *   <li>稳定码需要具体上下文（如 GIT_BRANCH_NOT_FOUND 的仓库/分支名）→ 对持久化原文脱敏后展示；</li>
     *   <li>未命中稳定码（白名单外的内部码）→ 对持久化原文脱敏后展示，保留真实原因而非通用兜底。</li>
     * </ul>
     */
    private static String sanitizedReason(String persistedReason, String failureCode) {
        if (failureCode != null && !"GIT_BRANCH_NOT_FOUND".equals(failureCode)) {
            // 稳定码能映射到受控文案：直接使用，绝不回显持久化原文（可能含模型内部细节）。
            return ExecutionContentSanitizer.userFailureDescription(failureCode);
        }
        String sanitized = ExecutionContentSanitizer.sanitize(persistedReason).strip();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500) + "…";
    }

    /**
     * 判断失败是否属于交付阶段（Diff/MR 生成或推送）。
     */
    private static boolean isDeliveryFailure(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return false;
        }
        String upper = failureCode.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("TASK_FINALIZATION") || upper.startsWith("FINAL_")
                || upper.contains("DIFF") || upper.contains("DELIVERY") || upper.contains("MR_");
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
