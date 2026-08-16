package qg.qgent.orchestration.preview;

import org.junit.jupiter.api.Test;
import qg.qgent.dto.WorkspaceDiffPreviewFileResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiffPatchFileParser 测试（阶段 E）：把受控 git 统一 diff 解析为结构化文件条目，
 * 正确处理 worker 的 {@code ===== repo =====} 分隔、new/deleted/rename/binary 标记与增删统计。
 */
class DiffPatchFileParserTest {

    @Test
    void nullOrBlankPatchYieldsEmptyList() {
        assertThat(DiffPatchFileParser.parse(null)).isEmpty();
        assertThat(DiffPatchFileParser.parse("  ")).isEmpty();
    }

    @Test
    void parsesAddedModifiedAndDeletedFilesAcrossRepoSeparators() {
        String patch = "===== repo-a =====\n"
                + "diff --git a/A.java b/A.java\n"
                + "index 111..222 100644\n"
                + "--- a/A.java\n"
                + "+++ b/A.java\n"
                + "@@ -1,2 +1,3 @@\n"
                + " context\n"
                + "-old\n"
                + "+new\n"
                + "+extra\n"
                + "===== repo-b =====\n"
                + "diff --git a/B.java b/B.java\n"
                + "new file mode 100644\n"
                + "index 000..333\n"
                + "--- /dev/null\n"
                + "+++ b/B.java\n"
                + "@@ -0,0 +1,1 @@\n"
                + "+only\n"
                + "diff --git a/C.java b/C.java\n"
                + "deleted file mode 100644\n"
                + "index 444..000\n"
                + "--- a/C.java\n"
                + "+++ /dev/null\n"
                + "@@ -1,2 +0,0 @@\n"
                + "-gone1\n"
                + "-gone2\n";

        List<WorkspaceDiffPreviewFileResponse> files = DiffPatchFileParser.parse(patch);

        assertThat(files).hasSize(3);
        assertThat(files.get(0).getPath()).isEqualTo("A.java");
        assertThat(files.get(0).getChangeType()).isEqualTo("MODIFIED");
        assertThat(files.get(0).getAdditions()).isEqualTo(2);
        assertThat(files.get(0).getDeletions()).isEqualTo(1);
        assertThat(files.get(1).getPath()).isEqualTo("B.java");
        assertThat(files.get(1).getChangeType()).isEqualTo("ADDED");
        assertThat(files.get(1).getAdditions()).isEqualTo(1);
        assertThat(files.get(1).getDeletions()).isZero();
        assertThat(files.get(2).getPath()).isEqualTo("C.java");
        assertThat(files.get(2).getChangeType()).isEqualTo("DELETED");
        assertThat(files.get(2).getAdditions()).isZero();
        assertThat(files.get(2).getDeletions()).isEqualTo(2);
    }

    @Test
    void binaryFileIsFlaggedWithoutLineCounts() {
        String patch = "diff --git a/img.png b/img.png\n"
                + "new file mode 100644\n"
                + "index 000..abc\n"
                + "Binary files /dev/null and b/img.png differ\n";

        List<WorkspaceDiffPreviewFileResponse> files = DiffPatchFileParser.parse(patch);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getPath()).isEqualTo("img.png");
        assertThat(files.get(0).getChangeType()).isEqualTo("ADDED");
        assertThat(files.get(0).getBinary()).isTrue();
        assertThat(files.get(0).getAdditions()).isZero();
        assertThat(files.get(0).getDeletions()).isZero();
    }

    @Test
    void renameUsesNewPathAndRenameChangeType() {
        String patch = "diff --git a/old-name.java b/new-name.java\n"
                + "similarity index 90%\n"
                + "rename from old-name.java\n"
                + "rename to new-name.java\n"
                + "index 111..222 100644\n"
                + "--- a/old-name.java\n"
                + "+++ b/new-name.java\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-old\n"
                + "+new\n";

        List<WorkspaceDiffPreviewFileResponse> files = DiffPatchFileParser.parse(patch);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getPath()).isEqualTo("new-name.java");
        assertThat(files.get(0).getChangeType()).isEqualTo("RENAMED");
        assertThat(files.get(0).getAdditions()).isEqualTo(1);
        assertThat(files.get(0).getDeletions()).isEqualTo(1);
    }

    @Test
    void quotedPathIsUnquoted() {
        String patch = "diff --git \"a/foo bar.txt\" \"b/foo bar.txt\"\n"
                + "--- \"a/foo bar.txt\"\n"
                + "+++ \"b/foo bar.txt\"\n"
                + "@@ -1 +1 @@\n"
                + "-a\n"
                + "+b\n";

        List<WorkspaceDiffPreviewFileResponse> files = DiffPatchFileParser.parse(patch);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).getPath()).isEqualTo("foo bar.txt");
        assertThat(files.get(0).getAdditions()).isEqualTo(1);
        assertThat(files.get(0).getDeletions()).isEqualTo(1);
    }
}
