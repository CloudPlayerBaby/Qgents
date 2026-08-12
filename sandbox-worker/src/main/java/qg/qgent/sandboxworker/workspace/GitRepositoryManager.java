package qg.qgent.sandboxworker.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 从 Worker 私有的共享 Git Store 创建 Workspace 独立仓库副本。
 * 独立副本不使用硬链接或 alternates，Sandbox 即使破坏自身 .git 目录也不会影响共享 Git Store。
 */
@Component
@RequiredArgsConstructor
public class GitRepositoryManager {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private final SandboxWorkerProperties properties;

    /**
     * 克隆独立仓库并切换到指定功能分支。
     * 基线和已有功能分支都先在受控 Git Store 中解析为提交编号，避免把未校验引用直接交给 clone。
     */
    public String create(UUID repositoryId, Path target, String baseRef, String sourceBranch) {
        Path gitStore = gitStore(repositoryId);
        if (!Files.isDirectory(gitStore)) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "GIT_STORE_NOT_FOUND", "共享 Git 仓库尚未准备完成");
        }
        if (Files.exists(target)) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "REPOSITORY_PATH_EXISTS", "Workspace 仓库目录已经存在");
        }

        String commit = resolveCommit(gitStore, "refs/heads/" + sourceBranch);
        if (commit == null) {
            commit = resolveCommit(gitStore, baseRef);
        }
        if (commit == null) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "GIT_BASE_REF_NOT_FOUND", "无法在共享 Git 仓库中解析基线引用");
        }

        requireSuccess(run(List.of("git", "clone", "--no-hardlinks", "--no-checkout",
                gitStore.toString(), target.toString())), "REPOSITORY_CLONE_FAILED", "无法创建 Workspace 独立仓库");
        try {
            requireSuccess(run(List.of("git", "-C", target.toString(), "checkout", "-B", sourceBranch, commit)),
                    "REPOSITORY_CHECKOUT_FAILED", "无法切换 Workspace 功能分支");
            requireSuccess(run(List.of("git", "-C", target.toString(), "remote", "remove", "origin")),
                    "REPOSITORY_CONFIG_FAILED", "无法移除共享 Git Store 的本地地址");
            requireSuccess(run(List.of("git", "-C", target.toString(), "config", "user.name", "Qgents Agent")),
                    "REPOSITORY_CONFIG_FAILED", "无法配置 Git 提交身份");
            requireSuccess(run(List.of("git", "-C", target.toString(), "config", "user.email",
                    "agent@qgents.local")), "REPOSITORY_CONFIG_FAILED", "无法配置 Git 提交身份");
            return head(target);
        } catch (RuntimeException exception) {
            deleteDirectory(target);
            throw exception;
        }
    }

    /** 返回 Workspace 独立仓库当前 HEAD。 */
    public String head(Path repository) {
        CommandResult result = run(List.of("git", "-C", repository.toString(), "rev-parse", "HEAD"));
        requireSuccess(result, "REPOSITORY_INVALID", "无法读取 Workspace 仓库 HEAD");
        return result.stdout().trim();
    }

    private String resolveCommit(Path gitStore, String reference) {
        CommandResult result = run(List.of("git", "--git-dir", gitStore.toString(),
                "rev-parse", "--verify", reference + "^{commit}"));
        return result.exitCode() == 0 ? result.stdout().trim() : null;
    }

    private Path gitStore(UUID repositoryId) {
        return Path.of(properties.getGitStoreRoot()).toAbsolutePath().normalize()
                .resolve(repositoryId + ".git").normalize();
    }

    private void requireSuccess(CommandResult result, String code, String fallbackMessage) {
        if (result.exitCode() == 0) {
            return;
        }
        throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, fallbackMessage);
    }

    private CommandResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outputReader = Thread.ofVirtual().start(() -> transfer(process.getInputStream(), stdout));
            Thread errorReader = Thread.ofVirtual().start(() -> transfer(process.getErrorStream(), stderr));
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new WorkerException(HttpStatus.GATEWAY_TIMEOUT,
                        "GIT_COMMAND_TIMEOUT", "Git 仓库操作执行超时");
            }
            outputReader.join();
            errorReader.join();
            return new CommandResult(process.exitValue(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } catch (WorkerException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerException(HttpStatus.BAD_GATEWAY,
                    "GIT_COMMAND_INTERRUPTED", "Git 仓库操作被中断");
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.BAD_GATEWAY,
                    "GIT_COMMAND_FAILED", "无法执行受控 Git 仓库操作");
        }
    }

    private void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            input.transferTo(output);
        } catch (Exception ignored) {
            // 进程退出或被强制终止时，读取线程可以直接结束。
        }
    }

    private void deleteDirectory(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // 上层会继续清理整个 Workspace，并保留原始 Git 异常。
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
