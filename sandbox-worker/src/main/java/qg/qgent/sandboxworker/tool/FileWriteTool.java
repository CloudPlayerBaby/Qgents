package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * 使用旧内容哈希校验和原子替换写入 UTF-8 文本文件。
 */
@Component
@RequiredArgsConstructor
public class FileWriteTool implements SandboxTool {
    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.write";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.string(arguments, "path", 1024);
        String expectedHash = ToolArguments.string(arguments, "expectedHash", 64);
        String content = ToolArguments.optionalString(arguments, "content", "", 2 * 1024 * 1024);
        Path target = files.resolveForWrite(context.getLocalRepository(), relativePath);
        try {
            byte[] previous = Files.exists(target) ? Files.readAllBytes(target) : new byte[0];
            String actualHash = FileReadTool.sha256(previous);
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new WorkerException(CONFLICT, "FILE_HASH_MISMATCH", "文件已经发生变化，请重新读取后再写入");
            }
            byte[] next = content.getBytes(StandardCharsets.UTF_8);
            Path temporary = Files.createTempFile(target.getParent(), ".qgents-", ".tmp");
            try {
                Files.write(temporary, next);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return ToolResult.value(Map.of("path", relativePath, "sha256", FileReadTool.sha256(next),
                    "bytes", next.length));
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("写入文件失败", exception);
        }
    }
}
