package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.result.ReviewResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReviewVerdictComputer 单元测试（宽松版）：severity 为唯一权威，空 findings 直通、
 * 风格白名单降级、无任何关键词升格。纯逻辑，无 Spring、无 LLM。
 */
class ReviewVerdictComputerTest {

    private final ReviewVerdictComputer computer = new ReviewVerdictComputer();

    private static ReviewResult.Finding finding(String severity, String issue) {
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity(severity);
        finding.setIssue(issue);
        return finding;
    }

    @Test
    void nullFindingsPass() {
        ReviewVerdictComputer.Verdict verdict = computer.compute(null);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.normalizedFindings()).isEmpty();
    }

    @Test
    void emptyFindingsPass() {
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of());

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.normalizedFindings()).isEmpty();
    }

    @Test
    void allMinorFindingsPass() {
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(
                finding("MINOR", "method name unclear")));

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void minorWithCorrectnessWordPassesSinceLenient() {
        // 宽松版不升格：MINOR 即使提到空指针也放行，只信 LLM 的严重度标签。
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(
                finding("MINOR", "null check missing")));

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.normalizedFindings().get(0).getSeverity()).isEqualTo("MINOR");
    }

    @Test
    void infoWithSecurityWordPassesSinceLenient() {
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(
                finding("INFO", "疑似越权访问风险")));

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.normalizedFindings().get(0).getSeverity()).isEqualTo("INFO");
    }

    @Test
    void majorStyleFindingIsDowngradedToMinorAndPasses() {
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(
                finding("MAJOR", "unused import in this file")));

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.normalizedFindings().get(0).getSeverity()).isEqualTo("MINOR");
    }

    @Test
    void majorCorrectnessFindingStillFails() {
        // MAJOR 且文本无风格词 → 保持 MAJOR → 失败（需求未实现这类由 LLM 标签决定）。
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(
                finding("MAJOR", "null check missing")));

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.normalizedFindings().get(0).getSeverity()).isEqualTo("MAJOR");
    }

    @Test
    void blockerIsNeverDowngradedEvenWithStyleText() {
        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(
                finding("BLOCKER", "comment style violation")));

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.normalizedFindings().get(0).getSeverity()).isEqualTo("BLOCKER");
    }

    @Test
    void normalizedFindingKeepsOtherFields() {
        ReviewResult.Finding original = new ReviewResult.Finding();
        original.setSeverity("MAJOR");
        original.setFile("src/main/java/X.java");
        original.setLine(12);
        original.setIssue("unused import");
        original.setSuggestion("remove it");

        ReviewVerdictComputer.Verdict verdict = computer.compute(List.of(original));

        ReviewResult.Finding normalized = verdict.normalizedFindings().get(0);
        assertThat(normalized.getSeverity()).isEqualTo("MINOR");
        assertThat(normalized.getFile()).isEqualTo("src/main/java/X.java");
        assertThat(normalized.getLine()).isEqualTo(12);
        assertThat(normalized.getIssue()).isEqualTo("unused import");
        assertThat(normalized.getSuggestion()).isEqualTo("remove it");
    }
}
