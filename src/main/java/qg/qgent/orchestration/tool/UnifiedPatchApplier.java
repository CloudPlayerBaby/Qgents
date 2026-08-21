package qg.qgent.orchestration.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 受控的严格 unified diff 应用器：hunk 行号从 1 开始、声明行数必须与正文一致，按从文件
 * 末尾向前的顺序应用，保证多 hunk 之间行号互不干扰。禁止模糊匹配、自动偏移和冲突覆盖；
 * 任一步骤失败抛出 {@link UnifiedPatchException}，绝不返回部分结果。
 * <p>
 * 与 sandbox-worker 的 FilePatchTool.UnifiedPatch 保持同一语义，保证本地实现与 Worker 行为一致。
 */
public final class UnifiedPatchApplier {

    private UnifiedPatchApplier() {
    }

    /**
     * 对已有文本内容精确应用统一 Diff。
     *
     * @param content 现有 UTF-8 文本内容。
     * @param patch   统一 Diff 文本。
     * @return 应用后的内容；补丁无法精确应用时抛出 {@link UnifiedPatchException}。
     */
    public static String apply(String content, String patch) {
        List<Hunk> hunks = parse(patch);
        if (hunks.isEmpty()) {
            throw new UnifiedPatchException("补丁不包含有效 hunk");
        }
        return applyHunks(content, hunks);
    }

    private static List<Hunk> parse(String patch) {
        List<String> raw = splitLines(patch);
        List<Hunk> hunks = new ArrayList<>();
        int index = 0;
        while (index < raw.size()) {
            String line = raw.get(index);
            if (!line.startsWith("@@")) {
                index++;
                continue;
            }
            Header header;
            try {
                header = parseHeader(line);
            } catch (RuntimeException exception) {
                throw new UnifiedPatchException("hunk 头格式非法");
            }
            index++;
            List<String> oldLines = new ArrayList<>();
            List<String> newLines = new ArrayList<>();
            // old/new 侧末尾是否以换行结尾：unified diff 中 "\ No newline at end of file" 紧跟
            // 在最后一行后，标记该侧最后一行无换行。区分两侧，避免旧文件无换行时把新文件也写成无换行。
            boolean oldEndsWithNewline = true;
            boolean newEndsWithNewline = true;
            // 上一条非空 diff 行的标记（'-'/'+'/' '），用于判断 no-newline 属于哪一侧。
            char lastMark = ' ';
            while (index < raw.size() && !raw.get(index).startsWith("@@")) {
                String bodyLine = raw.get(index++);
                if (bodyLine.isEmpty()) {
                    continue;
                }
                if (bodyLine.equals("\\ No newline at end of file")) {
                    if (lastMark == '-') {
                        oldEndsWithNewline = false;
                    } else if (lastMark == '+') {
                        newEndsWithNewline = false;
                    }
                    continue;
                }
                char mark = bodyLine.charAt(0);
                String text = bodyLine.substring(1);
                if (mark == ' ') {
                    oldLines.add(text);
                    newLines.add(text);
                } else if (mark == '-') {
                    oldLines.add(text);
                } else if (mark == '+') {
                    newLines.add(text);
                } else {
                    throw new UnifiedPatchException("hunk 包含非法行");
                }
                lastMark = mark;
            }
            if (oldLines.size() != header.old().count() || newLines.size() != header.new_().count()) {
                throw new UnifiedPatchException("hunk 声明行数与正文不一致");
            }
            hunks.add(new Hunk(header.old().start(), oldLines, newLines, oldEndsWithNewline, newEndsWithNewline));
        }
        return hunks;
    }

    private static Header parseHeader(String line) {
        String inner = line.substring(2);
        int marker = inner.indexOf("@@");
        if (marker < 0) {
            throw new IllegalArgumentException("missing second @@");
        }
        String ranges = inner.substring(0, marker).trim();
        int plus = ranges.indexOf('+');
        if (plus < 0) {
            throw new IllegalArgumentException("missing + separator");
        }
        return new Header(parsePosition(ranges.substring(1, plus).trim()),
                parsePosition(ranges.substring(plus + 1).trim()));
    }

    private static Position parsePosition(String text) {
        int comma = text.indexOf(',');
        if (comma < 0) {
            return new Position(Integer.parseInt(text.trim()), 1);
        }
        return new Position(Integer.parseInt(text.substring(0, comma).trim()),
                Integer.parseInt(text.substring(comma + 1).trim()));
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return lines;
    }

    private static String applyHunks(String content, List<Hunk> hunks) {
        String separator = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = splitContent(content);
        List<Hunk> ordered = new ArrayList<>(hunks);
        ordered.sort(Comparator.comparingInt((Hunk hunk) -> hunk.oldStart()).reversed());
        // 初始末尾换行取文件现状；最底部（最小 oldStart）hunk 的 newEndsWithNewline 决定最终结果。
        boolean endsWithNewline = content.endsWith("\n");
        for (Hunk hunk : ordered) {
            int from = Math.max(0, hunk.oldStart() - 1);
            int to = from + hunk.oldLines().size();
            if (from > lines.size() || to > lines.size()
                    || !lines.subList(from, to).equals(hunk.oldLines())) {
                throw new UnifiedPatchException("补丁上下文与文件不一致（第 " + hunk.oldStart() + " 行）");
            }
            lines.subList(from, to).clear();
            lines.addAll(from, hunk.newLines());
        }
        // 底部 hunk（oldStart 最大的那个，位于文件末尾）显式声明新文件末尾是否换行；
        // 原实现按"排序后第一个处理的 hunk"判定，等价于取最大 oldStart。无底部 hunk 时保留现状。
        int bottomOldStart = ordered.stream().mapToInt(h -> h.oldStart()).max().orElse(-1);
        for (Hunk hunk : ordered) {
            if (hunk.oldStart() == bottomOldStart) {
                endsWithNewline = hunk.newEndsWithNewline();
                break;
            }
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                result.append(separator);
            }
            result.append(lines.get(i));
        }
        if (endsWithNewline && !lines.isEmpty()) {
            result.append(separator);
        }
        return result.toString();
    }

    private static List<String> splitContent(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                String line = content.substring(start, index);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                lines.add(line);
                start = index + 1;
            }
        }
        if (start < content.length()) {
            String line = content.substring(start);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * 补丁无法精确应用时抛出的受控异常，不携带任何文件内容或 Secret。
     */
    public static final class UnifiedPatchException extends RuntimeException {
        public UnifiedPatchException(String message) {
            super(message);
        }
    }

    private record Position(int start, int count) {
    }

    private record Header(Position old, Position new_) {
    }

    private record Hunk(int oldStart, List<String> oldLines, List<String> newLines,
                        boolean oldEndsWithNewline, boolean newEndsWithNewline) {
    }
}
