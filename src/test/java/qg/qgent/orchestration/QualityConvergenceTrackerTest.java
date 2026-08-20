package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QualityConvergenceTracker 单元测试：只有上一轮与本轮可修复项签名完全一致才判不收敛；
 * 签名只统计归一化后 BLOCKER/MAJOR（Review）或全部失败项（Test）；空签名永不判不收敛。
 * 纯逻辑，无 Spring、无 LLM。
 */
class QualityConvergenceTrackerTest {

    private final QualityConvergenceTracker tracker = new QualityConvergenceTracker();

    private static AgentRunOutcome reviewFailure(ReviewResult.Finding... findings) {
        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(OrchestrationPhase.REVIEWING);
        outcome.setOutcome(RunOutcome.FAILED_QUALITY);
        ReviewResult review = new ReviewResult();
        review.setSuccess(false);
        review.setNeedsCodingFix(true);
        review.setFindings(List.of(findings));
        outcome.setReviewResult(review);
        return outcome;
    }

    private static ReviewResult.Finding major(String file, String issue) {
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile(file);
        finding.setIssue(issue);
        return finding;
    }

    private static AgentRunOutcome testFailure(String name, String reason) {
        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(OrchestrationPhase.TESTING);
        outcome.setOutcome(RunOutcome.FAILED_QUALITY);
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setNeedsCodingFix(true);
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName(name);
        failure.setReason(reason);
        test.setFailures(List.of(failure));
        outcome.setTestResult(test);
        return outcome;
    }

    @Test
    void identicalReviewMajorFindingsAreStalled() {
        AgentRunOutcome round = reviewFailure(major("src/AuthService.java", "missing ownership check"));

        assertThat(tracker.hasNoProgress(round)).isFalse();
        tracker.record(round);
        assertThat(tracker.hasNoProgress(round)).isTrue();
    }

    @Test
    void differentFindingsAreProgress() {
        tracker.record(reviewFailure(major("src/AuthService.java", "missing ownership check")));

        assertThat(tracker.hasNoProgress(reviewFailure(major("src/AuthService.java", "null check missing")))).isFalse();
    }

    @Test
    void subsetShrinkIsProgress() {
        tracker.record(reviewFailure(major("a.java", "A"), major("b.java", "B")));

        assertThat(tracker.hasNoProgress(reviewFailure(major("a.java", "A")))).isFalse();
    }

    @Test
    void emptySignatureNeverStalled() {
        AgentRunOutcome noResult = new AgentRunOutcome();
        noResult.setPhase(OrchestrationPhase.REVIEWING);
        noResult.setOutcome(RunOutcome.FAILED_QUALITY);

        assertThat(tracker.hasNoProgress(noResult)).isFalse();
        tracker.record(noResult);
        assertThat(tracker.hasNoProgress(noResult)).isFalse();
    }

    @Test
    void testFailuresIdenticalAreStalled() {
        AgentRunOutcome round = testFailure("testExport", "expected 5 but got 4");

        assertThat(tracker.hasNoProgress(round)).isFalse();
        tracker.record(round);
        assertThat(tracker.hasNoProgress(round)).isTrue();
    }

    @Test
    void styleMajorIsExcludedFromSignature() {
        // MAJOR 命中风格词 → 归一化为 MINOR → 不参与收敛判定，防止把风格问题误判为不收敛。
        AgentRunOutcome style = reviewFailure(major("src/AuthService.java", "unused import in this file"));

        assertThat(tracker.hasNoProgress(style)).isFalse();
        tracker.record(style);
        assertThat(tracker.hasNoProgress(style)).isFalse();
    }

    @Test
    void clearResetsStallComparison() {
        AgentRunOutcome round = reviewFailure(major("src/AuthService.java", "missing ownership check"));
        tracker.record(round);
        tracker.clear();

        assertThat(tracker.hasNoProgress(round)).isFalse();
    }

    @Test
    void noProgressTerminatedFlagStartsFalseAndToggles() {
        assertThat(tracker.noProgressTerminated()).isFalse();
        tracker.markNoProgress();
        assertThat(tracker.noProgressTerminated()).isTrue();
    }
}
