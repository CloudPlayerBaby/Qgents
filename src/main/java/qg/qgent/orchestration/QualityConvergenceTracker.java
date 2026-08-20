package qg.qgent.orchestration;

import qg.qgent.orchestration.agent.ReviewVerdictComputer;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 质量循环"不收敛"判定：记录上一轮质量失败（Test/Review）的可修复项签名，若本轮与上一轮
 * 非空签名<b>完全一致</b>，视为无进展，供 {@link TaskOrchestrator} 提前终止质量循环——同一
 * 缺陷反复打回只会空转（模型修不动，或该 MAJOR 本身是误报），提前终止既省 LLM 调用又让任务
 * 快速落终态，避免"总是卡在 review/test"。
 * <p>
 * 签名只统计真正驱动 requeue 的项，与宽松化 {@link ReviewVerdictComputer} 保持一致：
 * <ul>
 *   <li>REVIEWING：取归一化后仍为 BLOCKER/MAJOR 的 finding（风格类 MAJOR 已降级为 MINOR，
 *       不参与收敛判定，防止把"换了个风格问题"误判为不收敛）；</li>
 *   <li>TESTING：取全部失败项（测试失败没有风格概念，都是要修的）；</li>
 *   <li>签名为空（无 findings/无失败项、或全部被降级）时永不判不收敛。</li>
 * </ul>
 * 判定刻意保守：仅当上一轮与本轮签名<b>完全一致</b>才判不收敛；子集缩小（有修复进展）、
 * 新增项、issue 文本变化都视为仍在变化、继续循环。宁可多转一圈，也不误杀可收敛的任务。
 * 纯逻辑、无 I/O、不依赖 Spring，可独立单元测试。
 */
final class QualityConvergenceTracker {

    private final ReviewVerdictComputer verdictComputer = new ReviewVerdictComputer();
    /** 上一轮质量失败的可修复项签名；null 表示尚无上一轮（首次失败不判不收敛）。 */
    private Set<String> previousSignature;
    /** 本 orchestrate 会话是否已因不收敛提前终止（供终态文案区分"耗尽"与"无进展"）。 */
    private boolean noProgressTerminated;

    /**
     * 判定本轮质量失败是否与上一轮完全一致（无任何进展）。
     *
     * @param outcome 本轮 FAILED_QUALITY 的结果；非质量失败或无签名时返回 false。
     * @return true=上一轮与本轮非空签名一致，应立即终止；false=无依据或仍有变化。
     */
    boolean hasNoProgress(AgentRunOutcome outcome) {
        Set<String> current = signatureOf(outcome);
        if (current == null || current.isEmpty()) {
            return false;
        }
        return previousSignature != null && !previousSignature.isEmpty() && previousSignature.equals(current);
    }

    /**
     * requeue 继续时记录本轮签名，供下一轮比对。签名为空时清空上一轮记录。
     */
    void record(AgentRunOutcome outcome) {
        Set<String> current = signatureOf(outcome);
        previousSignature = (current == null || current.isEmpty()) ? null : new LinkedHashSet<>(current);
    }

    /**
     * 质量相位通过（SUCCEEDED）时清空签名，结束本段质量闭环，避免跨环节残留比对。
     */
    void clear() {
        previousSignature = null;
    }

    /**
     * 标记本会话已因不收敛提前终止。
     */
    void markNoProgress() {
        noProgressTerminated = true;
    }

    /**
     * 本会话是否已因不收敛提前终止。
     */
    boolean noProgressTerminated() {
        return noProgressTerminated;
    }

    /**
     * 计算可修复项签名：TESTING 取全部失败项（name|reason 去重）；REVIEWING 取归一化后
     * BLOCKER/MAJOR finding（file|line|severity|issue）。其余情况或签名恒空返回空集合。
     */
    private Set<String> signatureOf(AgentRunOutcome outcome) {
        if (outcome == null) {
            return Set.of();
        }
        if (outcome.getPhase() == OrchestrationPhase.TESTING && outcome.getTestResult() != null) {
            List<TestResult.Failure> failures = outcome.getTestResult().getFailures();
            if (failures == null || failures.isEmpty()) {
                return Set.of();
            }
            Set<String> signature = new LinkedHashSet<>();
            for (TestResult.Failure failure : failures) {
                signature.add(String.valueOf(failure.getName()) + "|" + String.valueOf(failure.getReason()));
            }
            return signature;
        }
        if (outcome.getPhase() == OrchestrationPhase.REVIEWING && outcome.getReviewResult() != null) {
            List<ReviewResult.Finding> findings = outcome.getReviewResult().getFindings();
            if (findings == null || findings.isEmpty()) {
                return Set.of();
            }
            Set<String> signature = new LinkedHashSet<>();
            for (ReviewResult.Finding finding : verdictComputer.compute(findings).normalizedFindings()) {
                if (isBlockerOrMajor(finding.getSeverity())) {
                    signature.add(String.valueOf(finding.getFile()) + "|" + finding.getLine() + "|"
                            + String.valueOf(finding.getSeverity()) + "|" + String.valueOf(finding.getIssue()));
                }
            }
            return signature;
        }
        return Set.of();
    }

    private boolean isBlockerOrMajor(String severity) {
        String effective = severity == null ? "" : severity.toUpperCase(Locale.ROOT);
        return "BLOCKER".equals(effective) || "MAJOR".equals(effective);
    }
}
