package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UnifiedPatchApplier} 纯单元测试：严格统一 Diff 的解析与应用、多 hunk 自底向上、
 * 插入/删除、CRLF 保留与失败原子性（失败即抛异常，不返回部分结果）。
 */
class UnifiedPatchApplierTest {

    @Test
    void appliesSingleHunkLeavingOtherSectionsUntouched() {
        String result = UnifiedPatchApplier.apply("line1\nline2\nline3\nline4\n", """
                --- a/README.md
                +++ b/README.md
                @@ -2,3 +2,3 @@
                 line2
                -line3
                +line3 changed
                 line4
                """);

        assertThat(result).isEqualTo("line1\nline2\nline3 changed\nline4\n");
    }

    @Test
    void appliesMultipleHunksFromBottomUp() {
        String result = UnifiedPatchApplier.apply("a\nb\nc\nd\ne\nf\n", """
                @@ -1,2 +1,2 @@
                 a
                -b
                +B
                @@ -5,2 +5,2 @@
                 e
                -f
                +F
                """);

        assertThat(result).isEqualTo("a\nB\nc\nd\ne\nF\n");
    }

    @Test
    void insertsAndDeletesLinesPrecisely() {
        String inserted = UnifiedPatchApplier.apply("x\ny\nz\n", "@@ -2,0 +2,1 @@\n+inserted\n");
        assertThat(inserted).isEqualTo("x\ninserted\ny\nz\n");

        String deleted = UnifiedPatchApplier.apply("x\ninserted\ny\nz\n", "@@ -2,1 +2,0 @@\n-inserted\n");
        assertThat(deleted).isEqualTo("x\ny\nz\n");
    }

    @Test
    void preservesCrlfSeparatorWhenContentIsCrlf() {
        String result = UnifiedPatchApplier.apply("a\r\nb\r\nc\r\n", "@@ -2,1 +2,1 @@\n-b\n+B\r\n");

        assertThat(result).isEqualTo("a\r\nB\r\nc\r\n");
    }

    @Test
    void rejectsInconsistentDeclaredLineCount() {
        assertThatThrownBy(() -> UnifiedPatchApplier.apply("a\nb\nc\n", "@@ -1,3 +1,3 @@\n-a\n"))
                .isInstanceOf(UnifiedPatchApplier.UnifiedPatchException.class)
                .hasMessageContaining("行数与正文不一致");
    }

    @Test
    void rejectsPatchWithoutHunks() {
        assertThatThrownBy(() -> UnifiedPatchApplier.apply("a\n", "--- a/x\n+++ b/x\n"))
                .isInstanceOf(UnifiedPatchApplier.UnifiedPatchException.class)
                .hasMessageContaining("不包含有效 hunk");
    }

    @Test
    void rejectsContextMismatchWithoutPartialResult() {
        assertThatThrownBy(() -> UnifiedPatchApplier.apply("aaa\nbbb\nccc\n",
                "@@ -1,3 +1,3 @@\n aaa\n-xxx\n+yyy\n ccc\n"))
                .isInstanceOf(UnifiedPatchApplier.UnifiedPatchException.class)
                .hasMessageContaining("上下文与文件不一致");
    }

    @Test
    void rejectsIllegalHunkHeader() {
        assertThatThrownBy(() -> UnifiedPatchApplier.apply("a\n", "@@ nope @@\n"))
                .isInstanceOf(UnifiedPatchApplier.UnifiedPatchException.class);
    }

    @Test
    void handlesNoNewlineMarkerByDroppingTrailingNewline() {
        String result = UnifiedPatchApplier.apply("a\nb\n", "@@ -2,1 +2,1 @@\n-b\n+c\n\\ No newline at end of file\n");

        assertThat(result).isEqualTo("a\nc");
    }
}
