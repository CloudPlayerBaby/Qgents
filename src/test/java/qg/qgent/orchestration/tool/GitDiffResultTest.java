package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitDiffResult 工厂与工作树摘要字段测试（阶段 D）：只读结果永远携带真实 base/head commit，
 * workingTreeHash 是幂等摘要、filesChanged/additions/deletions 是结构化统计，缺省为 0。
 */
class GitDiffResultTest {

    @Test
    void okThreeArgFactoryHasNoTreeStats() {
        GitDiffResult result = GitDiffResult.ok("patch", "base", "head");

        assertThat(result.ok()).isTrue();
        assertThat(result.diff()).isEqualTo("patch");
        assertThat(result.baseCommit()).isEqualTo("base");
        assertThat(result.headCommit()).isEqualTo("head");
        assertThat(result.workingTreeHash()).isNull();
        assertThat(result.filesChanged()).isZero();
        assertThat(result.additions()).isZero();
        assertThat(result.deletions()).isZero();
        assertThat(result.error()).isNull();
    }

    @Test
    void okSevenArgFactoryCarriesTreeStats() {
        GitDiffResult result = GitDiffResult.ok("patch", "base", "head", "sha256:abc", 3, 10, 2);

        assertThat(result.ok()).isTrue();
        assertThat(result.workingTreeHash()).isEqualTo("sha256:abc");
        assertThat(result.filesChanged()).isEqualTo(3);
        assertThat(result.additions()).isEqualTo(10);
        assertThat(result.deletions()).isEqualTo(2);
    }

    @Test
    void failureFactoryMarksUnavailable() {
        GitDiffResult result = GitDiffResult.failure("boom");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("boom");
        assertThat(result.diff()).isEmpty();
        assertThat(result.workingTreeHash()).isNull();
    }

    @Test
    void unavailableUsesReadyMessage() {
        GitDiffResult result = GitDiffResult.unavailable();

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not available");
    }
}
