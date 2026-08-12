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
 * 在受控共享 Git Store 与 Workspace 根目录之间管理 worktree。
 * 所有 Git 命令都使用参数数组，仓库和目标路径只能由服务端配置与资源编号推导。
 */
@Component
@RequiredArgsConstructor
public class GitWorktreeManager {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private final SandboxWorkerProperties properties;

    /**
     * 从共享裸仓库创建功能分支 worktree；目标目录必须尚不存在。
     */
    public String create(UUID repositoryId, Path target, String baseRef, String sourceBranch) {
        Path gitStore = gitStore(repositoryId);
        if (!Files.isDirectory(gitStore)) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "GIT_STORE_NOT_FOUND", "共享 Git 仓库尚未准备完成");
        }
        if (Files.exists(target)) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKTREE_PATH_EXISTS", "Workspace 仓库目录已经存在");
        }

        CommandResult branchCheck = run(List.of("git", "--git-dir", gitStore.toString(),
                "show-ref", "--verify", "--quiet", "refs/heads/" + sourceBranch));
        List<String> command = branchCheck.exitCode() == 0
                ? List.of("git", "--git-dir", gitStore.toString(), "worktree", "add", target.toString(), sourceBranch)
                : List.of("git", "--git-dir", gitStore.toString(), "worktree", "add", "-b", sourceBranch,
                        target.toString(), baseRef);
        CommandResult result = run(command);
        if (result.exitCode() != 0) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "WORKTREE_CREATE_FAILED", safeError(result));
        }
        return head(target);
    }

    /** 返回 worktree 当前 HEAD 提交。 */
    public String head(Path worktree) {
        CommandResult result = run(List.of("git", "-C", worktree.toString(), "rev-parse", "HEAD"));
        if (result.exitCode() != 0) {
            throw new WorkerException(HttpStatus.CONFLICT, "WORKTREE_INVALID", "无法读取 worktree HEAD");
        }
        return result.stdout().trim();
    }

    /**
     * 从共享仓库登记中移除 worktree。重复删除由上层根据目录存在性处理。
     */
    public void remove(UUID repositoryId, Path worktree) {
        Path gitStore = gitStore(repositoryId);
        if (!Files.isDirectory(gitStore)) {
            return;
        }
        CommandResult result = run(List.of("git", "--git-dir", gitStore.toString(),
                "worktree", "remove", "--force", worktree.toString()));
        if (result.exitCode() != 0 && Files.exists(worktree)) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKTREE_REMOVE_FAILED", "无法安全移除仓库 worktree");
        }
    }

    private Path gitStore(UUID repositoryId) {
        Path root = Path.of(properties.getGitStoreRoot()).toAbsolutePath().normalize();
        return root.resolve(repositoryId + ".git").normalize();
    }

    private CommandResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outputReader = Thread.ofVirtual().start(() -> transfer(process.getInputStream(), stdout));
            Thread errorReader = Thread.ofVirtual().start(() -> transfer(process.getErrorStream(), stderr));
            boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new WorkerException(HttpStatus.GATEWAY_TIMEOUT,
                        "GIT_COMMAND_TIMEOUT", "Git worktree 操作执行超时");
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
                    "GIT_COMMAND_INTERRUPTED", "Git worktree 操作被中断");
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.BAD_GATEWAY,
                    "GIT_COMMAND_FAILED", "无法执行受控 Git worktree 操作");
        }
    }

    private void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            input.transferTo(output);
        } catch (Exception ignored) {
            // 进程退出或被强制终止时，读取线程可以直接结束。
        }
    }

    private String safeError(CommandResult result) {
        String error = result.stderr().strip();
        return error.isEmpty() ? "Git 无法创建 worktree" : error.substring(0, Math.min(error.length(), 1000));
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
