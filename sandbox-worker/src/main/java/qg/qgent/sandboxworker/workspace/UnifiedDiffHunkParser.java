package qg.qgent.sandboxworker.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Git unified diff patch，提取每个文件的 hunk 与逐行变更，供 Diff 详情
 * / 前端"行级红绿 diff"渲染使用（契约 v1.9.4 §21.2 冻结结构）。
 * <p>
 * 每个 hunk 结构：
 * <pre>
 * { "header": { "oldStart": 1, "newStart": 1, "oldLines": 3, "newLines": 4 },
 *   "lines":  [ { "type": "CONTEXT", "oldLineNo": 1, "newLineNo": 1, "content": "..." },
 *               { "type": "DELETE",  "oldLineNo": 2, "newLineNo": null, "content": "..." },
 *               { "type": "ADD",     "oldLineNo": null, "newLineNo": 2, "content": "..." } ] }
 * </pre>
 * 行内容不含前缀符号（-/+ /空格），由 {@code type} 单独标识；行号按 unified diff
 * 语义递增：CONTEXT 新旧行号同时 +1，DELETE 仅旧行号 +1，ADD 仅新行号 +1。
 * {@code \ No newline at end of file} 标记、二进制文件块、无 hunk 的元数据块均不产出行。
 */
final class UnifiedDiffHunkParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiffHunkParser() {
    }

    /**
     * 解析完整 patch 文本，返回 {@code 文件路径 → hunks 列表}；无 hunk 的文件不出现在结果中。
     * 文件路径取块内 {@code +++ b/} 行（删除文件退化为 {@code --- a/} 行），与
     * {@code git diff --numstat -z} 的新路径保持一致。
     */
    static Map<String, List<Map<String, Object>>> parse(String patch) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (patch == null || patch.isBlank()) {
            return result;
        }
        String[] blocks = patch.split("\n(?=diff --git )");
        for (String block : blocks) {
            String path = filePath(block);
            if (path == null) {
                continue;
            }
            List<Map<String, Object>> hunks = parseHunks(block);
            if (!hunks.isEmpty()) {
                result.put(path, hunks);
            }
        }
        return result;
    }

    /**
     * 从单个文件块提取展示路径：优先新路径（{@code +++ b/}），删除文件无新路径时用旧路径
     * （{@code --- a/}）；块内无上述行（如纯 rename 无内容变更）返回 null。
     */
    private static String filePath(String block) {
        String newPath = prefixPath(block, "+++ b/");
        if (newPath != null) {
            return newPath;
        }
        return prefixPath(block, "--- a/");
    }

    private static String prefixPath(String block, String prefix) {
        for (String line : block.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * 解析一个文件块内的全部 hunk；跳过 hunk 头之前的元数据行（diff --git / index /
     * new file mode / similarity / rename / Binary files 等）。
     */
    private static List<Map<String, Object>> parseHunks(String block) {
        List<Map<String, Object>> hunks = new ArrayList<>();
        String[] lines = block.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            HunkHeader header = HunkHeader.parse(lines[index]);
            if (header == null) {
                index++;
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            int oldNo = header.oldStart();
            int newNo = header.newStart();
            index++;
            while (index < lines.length && !lines[index].startsWith("@@")) {
                String line = lines[index];
                if (!line.isEmpty() && line.charAt(0) != '\\') {
                    char kind = line.charAt(0);
                    if (kind == ' ') {
                        rows.add(row("CONTEXT", oldNo++, newNo++, line));
                    } else if (kind == '-') {
                        rows.add(row("DELETE", oldNo++, null, line));
                    } else if (kind == '+') {
                        rows.add(row("ADD", null, newNo++, line));
                    }
                }
                index++;
            }
            Map<String, Object> headerMap = new LinkedHashMap<>();
            headerMap.put("oldStart", header.oldStart());
            headerMap.put("newStart", header.newStart());
            headerMap.put("oldLines", header.oldCount());
            headerMap.put("newLines", header.newCount());
            Map<String, Object> hunk = new LinkedHashMap<>();
            hunk.put("header", headerMap);
            hunk.put("lines", rows);
            hunks.add(hunk);
        }
        return hunks;
    }

    private static Map<String, Object> row(String type, Integer oldLineNo, Integer newLineNo, String line) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("oldLineNo", oldLineNo);
        row.put("newLineNo", newLineNo);
        String content = line.length() > 1 ? line.substring(1) : "";
        if (content.endsWith("\r")) {
            content = content.substring(0, content.length() - 1);
        }
        row.put("content", content);
        return row;
    }

    /**
     * unified diff hunk 头：{@code @@ -oldStart[,oldCount] +newStart[,newCount] @@ [section]}；
     * count 缺省视为 1。
     */
    private record HunkHeader(int oldStart, int oldCount, int newStart, int newCount) {

        static HunkHeader parse(String line) {
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (!matcher.matches()) {
                return null;
            }
            int oldStart = Integer.parseInt(matcher.group(1));
            int oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            int newStart = Integer.parseInt(matcher.group(3));
            int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
            return new HunkHeader(oldStart, oldCount, newStart, newCount);
        }
    }
}
