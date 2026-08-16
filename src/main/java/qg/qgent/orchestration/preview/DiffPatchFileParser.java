package qg.qgent.orchestration.preview;

import qg.qgent.dto.WorkspaceDiffPreviewFileResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 git 统一 diff 文本解析为结构化文件列表（Preview {@code /files} 接口用，阶段 E）。
 * <p>
 * 支持 worker/本地聚合时插入的 {@code ===== repo =====} 分隔行（跳过并结束当前文件段），
 * 识别 {@code new file mode} / {@code deleted file mode} / {@code rename from|to} /
 * {@code Binary files ... differ} 标记，并统计 {@code +}/{@code -} 行数（排除
 * {@code ---}/{@code +++} 文件头）。纯字符串解析，不执行任何命令、不访问文件系统。
 */
public final class DiffPatchFileParser {

    private DiffPatchFileParser() {
    }

    public static List<WorkspaceDiffPreviewFileResponse> parse(String patch) {
        List<WorkspaceDiffPreviewFileResponse> files = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return files;
        }
        FileCursor current = null;
        for (String raw : patch.split("\n")) {
            String line = raw.replace("\r", "");
            if (line.startsWith("diff --git ")) {
                close(current, files);
                current = new FileCursor(pathOf(line));
            } else if (line.startsWith("=====")) {
                close(current, files);
                current = null;
            } else if (current != null) {
                if (line.startsWith("new file mode")) {
                    current.changeType = "ADDED";
                } else if (line.startsWith("deleted file mode")) {
                    current.changeType = "DELETED";
                } else if (line.startsWith("rename from ") || line.startsWith("rename to ")) {
                    current.changeType = "RENAMED";
                } else if (line.startsWith("Binary files ")) {
                    current.binary = true;
                } else if (line.startsWith("+++") || line.startsWith("---")) {
                    // 文件头，非增删行
                } else if (line.startsWith("+")) {
                    current.additions++;
                } else if (line.startsWith("-")) {
                    current.deletions++;
                }
            }
        }
        close(current, files);
        return files;
    }

    /**
     * 从 {@code diff --git a/old b/new} 取 b/ 侧路径；路径含空格时 git 会用 C 引号
     * （如 {@code diff --git "a/foo bar" "b/foo bar"}），两种形态都去掉引号解析。
     */
    private static String pathOf(String line) {
        String rest = line.substring("diff --git ".length());
        int idx = rest.lastIndexOf(" b/");
        if (idx >= 0) {
            return unquote(rest.substring(idx + 3));
        }
        int quoted = rest.lastIndexOf(" \"b/");
        if (quoted >= 0) {
            String path = unquote(rest.substring(quoted + 1));
            return path.startsWith("b/") ? path.substring(2) : path;
        }
        return null;
    }

    private static String unquote(String path) {
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1).replace("\\\"", "\"");
        }
        return path;
    }

    private static void close(FileCursor current, List<WorkspaceDiffPreviewFileResponse> files) {
        if (current == null || current.path == null || current.path.isBlank()) {
            return;
        }
        files.add(new WorkspaceDiffPreviewFileResponse(current.path, current.changeType,
                current.additions, current.deletions, current.binary));
    }

    private static final class FileCursor {
        private final String path;
        private String changeType = "MODIFIED";
        private int additions;
        private int deletions;
        private boolean binary;

        private FileCursor(String path) {
            this.path = path;
        }
    }
}
