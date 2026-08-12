package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GitDiffCommandBuilder 单元测试：验证合法 SHA/分支名构造 diff/rev-parse argv，
 * 拒绝空值、以 - 开头、含 .. / @{ / 空白 / 冒号等非法 ref，防止 option 注入。
 */
class GitDiffCommandBuilderTest {

    @Test
    void acceptsFullShaAsDiffBase() {
        List<String> argv = GitDiffCommandBuilder.diffCommand("0123456789abcdef0123456789abcdef01234567");

        assertThat(argv).containsExactly("git", "--no-pager", "diff", "--no-color", "--no-ext-diff",
                "0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    void acceptsShortShaAsDiffBase() {
        assertThat(GitDiffCommandBuilder.diffCommand("abc1234")).endsWith("abc1234");
    }

    @Test
    void acceptsBranchNameAsDiffBase() {
        assertThat(GitDiffCommandBuilder.diffCommand("main")).endsWith("main");
        assertThat(GitDiffCommandBuilder.diffCommand("feat/task-1")).endsWith("feat/task-1");
    }

    @Test
    void rejectsNullBlankAndWhitespaceBase() {
        assertThat(GitDiffCommandBuilder.isValidBase(null)).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("   ")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("a b")).isFalse();
    }

    @Test
    void rejectsOptionLikeBaseToPreventInjection() {
        assertThat(GitDiffCommandBuilder.isValidBase("-U")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("--output=x")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("-abc")).isFalse();
    }

    @Test
    void rejectsRangeOperatorColonAndRefLogInBase() {
        assertThat(GitDiffCommandBuilder.isValidBase("abc..def")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("origin:main")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("HEAD@{1}")).isFalse();
        assertThat(GitDiffCommandBuilder.isValidBase("../escape")).isFalse();
    }

    @Test
    void diffCommandThrowsOnInvalidBase() {
        assertThatThrownBy(() -> GitDiffCommandBuilder.diffCommand("-U"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe git diff base");
    }

    @Test
    void revParseHeadCommandResolvesHead() {
        assertThat(GitDiffCommandBuilder.revParseCommand("HEAD"))
                .containsExactly("git", "rev-parse", "HEAD");
    }

    @Test
    void revParseAcceptsShaAndBranch() {
        assertThat(GitDiffCommandBuilder.revParseCommand("abc1234")).endsWith("abc1234");
        assertThat(GitDiffCommandBuilder.revParseCommand("main")).endsWith("main");
    }

    @Test
    void revParseRejectsUnsafeRef() {
        assertThatThrownBy(() -> GitDiffCommandBuilder.revParseCommand("--flag"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
