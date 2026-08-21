package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 分页读取 UTF-8 文本文件并返回内容哈希。
 */
@Component
@RequiredArgsConstructor
public class FileReadTool implements SandboxTool {
    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.read";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.string(arguments, "path", 1024);
        int startLine = ToolArguments.integer(arguments, "startLine", 1, 1, Integer.MAX_VALUE);
        int lineCount = ToolArguments.integer(arguments, "lineCount", 200, 1, 1000);
        Path file = files.resolveExisting(context.getLocalRepository(), relativePath);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 2 * 1024 * 1024) {
                throw new IllegalArgumentException("文件超过 2 MiB，不能一次读取");
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            List<String> allLines = content.lines().toList();
            int from = Math.min(startLine - 1, allLines.size());
            int to = Math.min(from + lineCount, allLines.size());
            boolean endsWithNewline = bytes.length > 0 && (bytes[bytes.length - 1] == '\n');
            String newlineStyle = detectNewlineStyle(bytes);
            return ToolResult.value(Map.of(
                    "path", relativePath,
                    "sha256", sha256(bytes),
                    "startLine", startLine,
                    "totalLines", allLines.size(),
                    "lines", allLines.subList(from, to),
                    "truncated", to < allLines.size(),
                    "endsWithNewline", endsWithNewline,
                    "newlineStyle", newlineStyle));
        } catch (Exception exception) {
            throw new IllegalStateException("读取文件失败", exception);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("计算文件哈希失败", exception);
        }
    }

    /**
     * 检测文件换行风格：含 CRLF 序列记为 CRLF；否则含 LF 记为 LF；都不含记为 NONE（无换行符）。
     * 只做保守方向检测——CRLF 与 LF 混用时记为 CRLF（存在 CRLF 即说明文件是 CRLF 风格的），
     * 供模型在改写文件时保持原风格。
     */
    static String detectNewlineStyle(byte[] bytes) {
        boolean hasLf = false;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                if (i > 0 && bytes[i - 1] == '\r') {
                    return "CRLF";
                }
                hasLf = true;
            }
        }
        return hasLf ? "LF" : "NONE";
    }
}
