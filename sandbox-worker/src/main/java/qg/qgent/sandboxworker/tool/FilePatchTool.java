package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 对已有 UTF-8 文本文件精确应用统一 Diff（unified diff）局部修改。
 * <p>
 * 与 {@link FileWriteTool} 共享安全模型：路径经 {@link RepositoryFileResolver} 解析、
 * expectedHash 乐观并发控制、{@link FileWriteTool#atomicReplace} 原子替换与沙箱用户权限修复。
 * 与整文件替换不同，本工具只接受已有的普通文本文件：拒绝符号链接、目录、二进制/非 UTF-8 内容。
 * <p>
 * 补丁严格按声明行号与上下文精确应用，禁止模糊匹配、自动偏移和冲突覆盖；基础哈希不匹配
 * 或补丁无法精确应用时原文件内容不变，不会产生部分写入。不把原始 Patch 或完整文件内容写入日志。
 */
@Component
@RequiredArgsConstructor
public class FilePatchTool implements SandboxTool {
    private static final int MAX_PATCH_LENGTH = 1024 * 1024;
    /**
     * 目标文件大小上限，与主后端 {@code LocalWorkspaceCodeWriter.MAX_WRITE_BYTES} 一致。
     * 超过该上限时拒绝打补丁并提示改用整文件写入，避免对超大文件整读整写拖过执行
     * 超时后被误判为基础设施失败。
     */
    private static final int MAX_PATCH_TARGET_BYTES = 256 * 1024;

    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.patch";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.string(arguments, "path", 1024);
        String expectedHash = ToolArguments.string(arguments, "expectedHash", 64);
        String patch = ToolArguments.string(arguments, "patch", MAX_PATCH_LENGTH);
        Path target = files.resolveForWrite(context.getLocalRepository(), relativePath);
        try {
            requireExistingTextFile(target);
            if (Files.size(target) > MAX_PATCH_TARGET_BYTES) {
                throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID",
                        "目标文件超过 256KB，无法打补丁，请改用 file.write 提供完整文件内容");
            }
            byte[] previous = Files.readAllBytes(target);
            if (!FileReadTool.sha256(previous).equalsIgnoreCase(expectedHash)) {
                throw new WorkerException(CONFLICT, "FILE_HASH_MISMATCH", "文件已经发生变化，请重新读取后再写入");
            }
            String content;
            try {
                content = decodeUtf8(previous);
            } catch (CharacterCodingException exception) {
                throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID", "目标文件不是 UTF-8 文本");
            }
            String next = UnifiedPatch.apply(content, patch);
            byte[] nextBytes = next.getBytes(StandardCharsets.UTF_8);
            boolean changed = !Arrays.equals(previous, nextBytes);
            FileWriteTool.atomicReplace(target, nextBytes);
            return ToolResult.value(Map.of("path", relativePath,
                    "sha256", FileReadTool.sha256(nextBytes),
                    "bytes", nextBytes.length,
                    "changed", changed));
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("应用补丁失败", exception);
        }
    }

    private void requireExistingTextFile(Path target) {
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID", "目标必须是已有的普通文本文件");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 严格的 unified diff 解析与应用：hunk 行号从 1 开始，声明行数必须与正文一致；
     * 按从文件末尾向前的顺序应用，保证多 hunk 之间行号互不干扰。
     */
    private static final class UnifiedPatch {
        private UnifiedPatch() {
        }

        static String apply(String content, String patch) {
            List<Hunk> hunks = parse(patch);
            if (hunks.isEmpty()) {
                throw patchFailed("补丁不包含有效 hunk");
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
                    throw patchFailed("hunk 头格式非法");
                }
                index++;
                List<String> oldLines = new ArrayList<>();
                List<String> newLines = new ArrayList<>();
                boolean noNewline = false;
                while (index < raw.size() && !raw.get(index).startsWith("@@")) {
                    String bodyLine = raw.get(index++);
                    if (bodyLine.isEmpty()) {
                        continue;
                    }
                    if (bodyLine.equals("\\ No newline at end of file")) {
                        noNewline = true;
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
                        throw patchFailed("hunk 包含非法行");
                    }
                }
                if (oldLines.size() != header.old().count() || newLines.size() != header.new_().count()) {
                    throw patchFailed("hunk 声明行数与正文不一致");
                }
                hunks.add(new Hunk(header.old().start(), oldLines, newLines, noNewline));
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
            boolean endsWithNewline = content.endsWith("\n");
            boolean bottomHunk = true;
            for (Hunk hunk : ordered) {
                int from = Math.max(0, hunk.oldStart() - 1);
                int to = from + hunk.oldLines().size();
                if (from > lines.size() || to > lines.size()
                        || !lines.subList(from, to).equals(hunk.oldLines())) {
                    throw patchFailed("补丁上下文与文件不一致（第 " + hunk.oldStart() + " 行）");
                }
                lines.subList(from, to).clear();
                lines.addAll(from, hunk.newLines());
                if (bottomHunk && hunk.noNewline()) {
                    endsWithNewline = false;
                }
                bottomHunk = false;
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

        private static WorkerException patchFailed(String message) {
            return new WorkerException(UNPROCESSABLE_ENTITY, "FILE_PATCH_FAILED", message);
        }

        private record Position(int start, int count) {
        }

        private record Header(Position old, Position new_) {
        }

        private record Hunk(int oldStart, List<String> oldLines, List<String> newLines, boolean noNewline) {
        }
    }
}
