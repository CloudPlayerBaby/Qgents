package qg.qgent.sandboxworker.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UnifiedDiffHunkParser} 的聚焦测试：hunk 头（含省略 count 的简写）、逐行类型与
 * 行号递增、多 hunk、No newline 标记跳过、删除文件路径退化、二进制与纯 rename 块跳过。
 */
class UnifiedDiffHunkParserTest {

    @Test
    void parsesSingleHunkWithContextAddAndDelete() {
        String patch = """
                diff --git a/src/main.java b/src/main.java
                index 123..456 100644
                --- a/src/main.java
                +++ b/src/main.java
                @@ -10,4 +10,5 @@
                 public static void main(String[] args) {
                -    oldLine();
                +    newLine();
                +    anotherNew();
                 }
                """;

        Map<String, List<Map<String, Object>>> parsed = UnifiedDiffHunkParser.parse(patch);

        assertEquals(1, parsed.size());
        List<Map<String, Object>> hunks = parsed.get("src/main.java");
        assertEquals(1, hunks.size());
        Map<String, Object> header = (Map<String, Object>) hunks.get(0).get("header");
        assertEquals(10, header.get("oldStart"));
        assertEquals(4, header.get("oldLines"));
        assertEquals(10, header.get("newStart"));
        assertEquals(5, header.get("newLines"));

        List<Map<String, Object>> lines = (List<Map<String, Object>>) hunks.get(0).get("lines");
        assertEquals(5, lines.size());
        assertEquals("CONTEXT", lines.get(0).get("type"));
        assertEquals(10, lines.get(0).get("oldLineNo"));
        assertEquals(10, lines.get(0).get("newLineNo"));
        assertEquals("public static void main(String[] args) {", lines.get(0).get("content"));

        assertEquals("DELETE", lines.get(1).get("type"));
        assertEquals(11, lines.get(1).get("oldLineNo"));
        assertNull(lines.get(1).get("newLineNo"));
        assertEquals("    oldLine();", lines.get(1).get("content"));

        assertEquals("ADD", lines.get(2).get("type"));
        assertNull(lines.get(2).get("oldLineNo"));
        assertEquals(11, lines.get(2).get("newLineNo"));
        assertEquals("    newLine();", lines.get(2).get("content"));

        assertEquals("ADD", lines.get(3).get("type"));
        assertEquals(12, lines.get(3).get("newLineNo"));
        assertEquals("    anotherNew();", lines.get(3).get("content"));

        assertEquals("CONTEXT", lines.get(4).get("type"));
        assertEquals(12, lines.get(4).get("oldLineNo"));
        assertEquals(13, lines.get(4).get("newLineNo"));
        assertEquals("}", lines.get(4).get("content"));
    }

    @Test
    void parsesShorthandHunkHeaderWithOmittedCounts() {
        String patch = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -old
                +new
                """;

        Map<String, List<Map<String, Object>>> parsed = UnifiedDiffHunkParser.parse(patch);

        List<Map<String, Object>> hunks = parsed.get("a.txt");
        Map<String, Object> header = (Map<String, Object>) hunks.get(0).get("header");
        assertEquals(1, header.get("oldStart"));
        assertEquals(1, header.get("oldLines"));
        assertEquals(1, header.get("newStart"));
        assertEquals(1, header.get("newLines"));
    }

    @Test
    void parsesMultipleHunksInOneFile() {
        String patch = """
                diff --git a/multi.txt b/multi.txt
                --- a/multi.txt
                +++ b/multi.txt
                @@ -1,2 +1,2 @@
                 keep1
                -oldA
                +newA
                @@ -20,2 +20,2 @@
                 keep2
                -oldB
                +newB
                """;

        List<Map<String, Object>> hunks = UnifiedDiffHunkParser.parse(patch).get("multi.txt");

        assertEquals(2, hunks.size());
        Map<String, Object> secondHeader = (Map<String, Object>) hunks.get(1).get("header");
        assertEquals(20, secondHeader.get("oldStart"));
        assertEquals(20, secondHeader.get("newStart"));
    }

    @Test
    void skipsNoNewlineMarkerAndKeepsLineNumbering() {
        String patch = """
                diff --git a/end.txt b/end.txt
                --- a/end.txt
                +++ b/end.txt
                @@ -1,2 +1,2 @@
                 first
                -second
                \\ No newline at end of file
                +third
                """;

        List<Map<String, Object>> lines = (List<Map<String, Object>>) UnifiedDiffHunkParser.parse(patch)
                .get("end.txt").get(0).get("lines");

        assertEquals(3, lines.size(), "No newline 标记不得产出行");
        assertEquals("DELETE", lines.get(1).get("type"));
        assertEquals("ADD", lines.get(2).get("type"));
        assertEquals(2, lines.get(2).get("newLineNo"), "No newline 标记不消耗行号");
    }

    @Test
    void deletedFileFallsBackToOldPath() {
        String patch = """
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                index 123..000 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -remove me
                """;

        Map<String, List<Map<String, Object>>> parsed = UnifiedDiffHunkParser.parse(patch);

        assertTrue(parsed.containsKey("gone.txt"), "删除文件应退化使用 a/ 路径");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) parsed.get("gone.txt").get(0).get("lines");
        assertEquals(1, lines.size());
        assertEquals("DELETE", lines.get(0).get("type"));
        assertNull(lines.get(0).get("newLineNo"));
    }

    @Test
    void skipsBinaryAndPureRenameBlocks() {
        String patch = """
                diff --git a/pic.png b/pic.png
                index 123..456 100644
                Binary files a/pic.png and b/pic.png differ
                diff --git a/old.txt b/new.txt
                similarity index 100%
                rename from old.txt
                rename to new.txt
                """;

        Map<String, List<Map<String, Object>>> parsed = UnifiedDiffHunkParser.parse(patch);

        assertTrue(parsed.isEmpty(), "二进制与纯 rename 块不得产出 hunk");
    }

    @Test
    void emptyOrNullPatchReturnsEmptyMap() {
        assertTrue(UnifiedDiffHunkParser.parse(null).isEmpty());
        assertTrue(UnifiedDiffHunkParser.parse("").isEmpty());
        assertTrue(UnifiedDiffHunkParser.parse("   \n  ").isEmpty());
    }

    @Test
    void multipleFilesAreKeyedByNewPath() {
        String patch = """
                diff --git a/one.txt b/one.txt
                --- a/one.txt
                +++ b/one.txt
                @@ -1 +1 @@
                -a
                +b
                diff --git a/two.txt b/two.txt
                --- a/two.txt
                +++ b/two.txt
                @@ -1 +1 @@
                -x
                +y
                """;

        Map<String, List<Map<String, Object>>> parsed = UnifiedDiffHunkParser.parse(patch);

        assertEquals(2, parsed.size());
        assertTrue(parsed.containsKey("one.txt"));
        assertTrue(parsed.containsKey("two.txt"));
        assertEquals(1, parsed.get("one.txt").size());
        assertEquals(1, parsed.get("two.txt").size());
    }
}
