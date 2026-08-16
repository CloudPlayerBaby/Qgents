package qg.qgent.orchestration.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.service.WorkspaceService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * WorkspaceCodeWriter 的本地最小实现，安全约束即本类的核心：
 * <ul>
 *   <li>工作区根目录由 {@link WorkspaceService} 统一解析（app.workspace.base-dir/{storageKey}），
 *       只接受 found 的 workspace，未配置 base-dir 或 Workspace 不存在时直接拒绝；</li>
 *   <li>路径归一化后必须仍位于 Workspace 根目录内，拒绝绝对路径与 {@code ..} 越界（防路径穿越）；</li>
 *   <li>单文件内容上限 256KB，拒绝超大写入；</li>
 *   <li>父目录不存在时按约定自动创建（mkdirs）；</li>
 *   <li>写入失败返回明确错误，绝不静默丢弃。</li>
 *   <li>patchFile 只修改已有普通文本文件：拒绝符号链接（含父目录符号链接越界）、目录、
 *       二进制/非法 UTF-8，严格按 {@link UnifiedPatchApplier} 应用统一 Diff，hash 或上下文
 *       不匹配时原文件保持不变；写回采用临时文件 + 原子替换，失败时原文件不被截断；</li>
 *   <li>workspace 不可解析或文件系统写入异常属于基础设施失败（{@link WorkspaceWriteResult#infraFail}），
 *       应由 Agent 映射 FAILED_INFRASTRUCTURE；参数/路径/大小/补丁错误属于工具级失败，可回灌模型纠正。</li>
 * </ul>
 * 真实 Sandbox 接入后由沙箱内实现替换本类，安全边界保持不变。
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "false", matchIfMissing = true)
public class LocalWorkspaceCodeWriter implements WorkspaceCodeWriter {

    /**
     * 单次写入内容的最大字节数。
     */
    private static final int MAX_WRITE_BYTES = 256 * 1024;
    /**
     * 单次补丁文本的最大字节数，与 Worker file.patch 契约一致。
     */
    private static final int MAX_PATCH_BYTES = 1024 * 1024;

    private final WorkspaceService workspaceService;

    public LocalWorkspaceCodeWriter(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public WorkspaceWriteResult writeFile(UUID workspaceId, String path, String content) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (content == null) {
            return WorkspaceWriteResult.fail(path, "content must not be null");
        }
        Path root = workspaceRoot(workspaceId);
        if (root == null) {
            return WorkspaceWriteResult.infraFail(path, "workspace root is not available");
        }
        Path target = resolveSafe(root, path);
        if (target == null) {
            return WorkspaceWriteResult.fail(path, "path escapes workspace root or is absolute");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
            return WorkspaceWriteResult.fail(path, "content exceeds 256KB limit");
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return WorkspaceWriteResult.ok(path);
        } catch (IOException e) {
            return WorkspaceWriteResult.infraFail(path, "write failed: " + e.getMessage());
        }
    }

    @Override
    public WorkspaceWriteResult patchFile(UUID workspaceId, String path, String expectedHash, String patch) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            return WorkspaceWriteResult.fail(path, "expectedHash must be 64 hex chars");
        }
        if (patch == null || patch.isBlank()) {
            return WorkspaceWriteResult.fail(path, "patch must not be blank");
        }
        if (patch.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {
            return WorkspaceWriteResult.fail(path, "patch exceeds 1MB limit");
        }
        Path root = workspaceRoot(workspaceId);
        if (root == null) {
            return WorkspaceWriteResult.infraFail(path, "workspace root is not available");
        }
        Path target;
        try {
            target = resolvePatchTarget(root, path);
        } catch (InvalidPathException e) {
            return WorkspaceWriteResult.fail(path, "path contains invalid characters");
        }
        if (target == null) {
            return WorkspaceWriteResult.fail(path, "path escapes workspace root or is absolute");
        }
        try {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return WorkspaceWriteResult.fail(path, "target must be an existing regular file");
            }
            if (Files.size(target) > MAX_WRITE_BYTES) {
                return WorkspaceWriteResult.fail(path, "file exceeds 256KB limit");
            }
            byte[] previous = Files.readAllBytes(target);
            if (!Sha256.hex(previous).equalsIgnoreCase(expectedHash)) {
                return WorkspaceWriteResult.fail(path, "file has changed since read, re-read then patch");
            }
            String content;
            try {
                content = decodeStrictUtf8(previous);
            } catch (CharacterCodingException e) {
                return WorkspaceWriteResult.fail(path, "file is not UTF-8 text");
            }
            String next;
            try {
                next = UnifiedPatchApplier.apply(content, patch);
            } catch (UnifiedPatchApplier.UnifiedPatchException e) {
                return WorkspaceWriteResult.fail(path, e.getMessage());
            }
            byte[] nextBytes = next.getBytes(StandardCharsets.UTF_8);
            if (nextBytes.length > MAX_WRITE_BYTES) {
                return WorkspaceWriteResult.fail(path, "patched file exceeds 256KB limit");
            }
            atomicReplace(target, nextBytes);
            return WorkspaceWriteResult.ok(path);
        } catch (IOException e) {
            return WorkspaceWriteResult.infraFail(path, "patch failed: " + e.getMessage());
        }
    }

    /**
     * 通过临时文件 + 原子替换写回，任一步骤失败都保证目标文件保持原样，不会出现部分写入。
     */
    private static void atomicReplace(Path target, byte[] next) throws IOException {
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, ".qgents-", ".tmp");
        try {
            Files.write(temporary, next);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 解析 Patch 目标并校验父目录真实路径仍位于 Workspace 真实根目录内。
     * <p>
     * resolveSafe 是词法校验，父目录若为指向外部的符号链接（如 {@code sub -> 外部目录}），
     * {@code sub/file} 仍能通过词法检查。这里用 toRealPath 解析父目录真实路径，越界返回 null；
     * 路径含非法字符（如 NUL）时抛出 {@link InvalidPathException}，由调用方归类为工具错误。
     */
    private Path resolvePatchTarget(Path root, String path) {
        Path candidate = Path.of(path);
        if (candidate.isAbsolute()) {
            return null;
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        Path parent = resolved.getParent();
        if (parent == null) {
            return null;
        }
        try {
            Path realRoot = root.toRealPath();
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realRoot)) {
                return null;
            }
            return realParent.resolve(resolved.getFileName());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 严格 UTF-8 解码，拒绝畸形或不可映射字节。
     */
    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 解析 Workspace 根目录；workspace 不存在或不可解析时返回 null（目录允许尚未创建）。
     */
    private Path workspaceRoot(UUID workspaceId) {
        WorkspaceService.WorkspaceResolution resolution = workspaceService.resolve(workspaceId);
        return resolution.found() ? resolution.root() : null;
    }

    /**
     * 路径归一化并校验仍在根目录内，拒绝绝对路径与目录穿越。
     */
    private Path resolveSafe(Path root, String path) {
        Path candidate = Path.of(path);
        if (candidate.isAbsolute()) {
            return null;
        }
        Path resolved = root.resolve(path).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }
}
