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
import java.util.Arrays;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 受控的「确保文件以换行结尾」修复动作：按当前内容哈希校验后，若文件不以换行结尾则追加一个换行。
 * <p>
 * 这是质量修复链路的确定性动作——避免 AI 手工重拼完整文件或反复生成错误的 unified patch。
 * 幂等：文件已以换行结尾时返回 changed=false；哈希不匹配抛 {@code FILE_HASH_MISMATCH}（与
 * file.patch/file.write 同一乐观并发控制）。追加的换行风格与文件既有风格一致（CRLF 文件追加 CRLF）。
 */
@Component
@RequiredArgsConstructor
public class FileEnsureTrailingNewlineTool implements SandboxTool {
    private static final int MAX_TARGET_BYTES = 2 * 1024 * 1024;

    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.ensure_trailing_newline";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.string(arguments, "path", 1024);
        String expectedHash = ToolArguments.string(arguments, "expectedHash", 64);
        Path target = files.resolveForWrite(context.getLocalRepository(), relativePath);
        try {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID",
                        "目标必须是已有的普通文本文件");
            }
            if (Files.size(target) > MAX_TARGET_BYTES) {
                throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID",
                        "目标文件超过 2 MiB，无法执行换行修复");
            }
            byte[] previous = Files.readAllBytes(target);
            if (!FileReadTool.sha256(previous).equalsIgnoreCase(expectedHash)) {
                throw new WorkerException(CONFLICT, "FILE_HASH_MISMATCH", "文件已经发生变化，请重新读取后再写入");
            }
            decodeUtf8(previous);
            if (previous.length == 0 || previous[previous.length - 1] == '\n') {
                return ToolResult.value(Map.of("path", relativePath, "changed", false,
                        "endsWithNewline", true));
            }
            // 换行风格按文件既有内容检测（含 CRLF 序列视为 CRLF 文件），追加与之一致的换行。
            String separator = containsCrLf(previous) ? "\r\n" : "\n";
            byte[] next = Arrays.copyOf(previous, previous.length + separator.length());
            System.arraycopy(separator.getBytes(StandardCharsets.UTF_8), 0, next, previous.length,
                    separator.length());
            FileWriteTool.atomicReplace(target, next);
            return ToolResult.value(Map.of("path", relativePath,
                    "sha256", FileReadTool.sha256(next),
                    "bytes", next.length,
                    "changed", true,
                    "endsWithNewline", true));
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("追加换行失败", exception);
        }
    }

    private static void decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        decoder.decode(ByteBuffer.wrap(bytes));
    }

    /** 内容是否含 CRLF 换行序列：含任一 CRLF 即视为 CRLF 风格文件，追加换行时保持一致。 */
    private static boolean containsCrLf(byte[] bytes) {
        for (int i = 0; i + 1 < bytes.length; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                return true;
            }
        }
        return false;
    }
}
