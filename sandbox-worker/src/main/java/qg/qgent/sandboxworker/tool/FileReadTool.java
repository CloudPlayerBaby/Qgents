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

/** 分页读取 UTF-8 文本文件并返回内容哈希。 */
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
            return ToolResult.value(Map.of(
                    "path", relativePath,
                    "sha256", sha256(bytes),
                    "startLine", startLine,
                    "totalLines", allLines.size(),
                    "lines", allLines.subList(from, to),
                    "truncated", to < allLines.size()));
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
}
