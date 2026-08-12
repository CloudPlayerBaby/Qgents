package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 受控构造 Git 参数数组的工具处理器。
 * Agent 只能选择已经注册的操作和结构化参数，不能提交任意 Git 命令字符串。
 */
@Component
@RequiredArgsConstructor
public class GitTool implements SandboxTool {
    private static final List<String> NAMES = List.of("git.status", "git.diff", "git.log", "git.add", "git.commit", "git.head", "git.push");
    private final CommandExecutor executor;

    @Override
    public String name() {
        return "git.multi";
    }

    /** 返回当前处理器支持的具体 Git 工具名称。 */
    public List<String> names() {
        return NAMES;
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        throw new UnsupportedOperationException("Git 工具必须通过具体名称调用");
    }

    /**
     * 根据具体工具名称构造参数数组并在目标仓库中执行。
     */
    public ToolResult execute(String name, ToolContext context, Map<String, Object> arguments)
            throws InterruptedException {
        if ("git.head".equals(name)) {
            return executeGitHead(context, new ArrayList<>(), new ArrayList<>());
        }
        if ("git.push".equals(name)) {
            return executeGitPush(context, arguments);
        }

        List<String> command = command(name, arguments);
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(), command,
                context.getTimeout());

        if ("git.commit".equals(name)) {
            if (result.getExitCode() == 0) {
                ToolResult headResult = executeGitHead(context, new ArrayList<>(result.getStandardOutput()), new ArrayList<>(result.getStandardError()));
                if (headResult.getExitCode() == 0) {
                    Map<String, Object> commitResult = new HashMap<>(headResult.getResult());
                    // Replace "headCommit" with "commitSha" for git.commit
                    commitResult.put("commitSha", commitResult.remove("headCommit"));
                    return new ToolResult(0, commitResult, headResult.getStandardOutput(), headResult.getStandardError());
                }
            }
        }

        return new ToolResult(result.getExitCode(), Map.of("lines", result.getStandardOutput()),
                result.getStandardOutput(), result.getStandardError());
    }

    private ToolResult executeGitHead(ToolContext context, List<String> stdout, List<String> stderr) throws InterruptedException {
        CommandExecutionResult shaResult = executor.execute(context.getSandbox(), context.getContainerRepository(), List.of("git", "rev-parse", "HEAD"), context.getTimeout());
        stdout.addAll(shaResult.getStandardOutput());
        stderr.addAll(shaResult.getStandardError());

        if (shaResult.getExitCode() != 0) {
            return new ToolResult(shaResult.getExitCode(), Map.of("error", "Failed to resolve HEAD", "details", shaResult.getStandardError()), stdout, stderr);
        }
        String headCommit = shaResult.getStandardOutput().isEmpty() ? "" : shaResult.getStandardOutput().get(0).trim();

        CommandExecutionResult branchResult = executor.execute(context.getSandbox(), context.getContainerRepository(), List.of("git", "symbolic-ref", "--short", "HEAD"), context.getTimeout());
        stdout.addAll(branchResult.getStandardOutput());
        stderr.addAll(branchResult.getStandardError());
        String branch = branchResult.getExitCode() == 0 && !branchResult.getStandardOutput().isEmpty() ? branchResult.getStandardOutput().get(0).trim() : "HEAD";

        CommandExecutionResult statusResult = executor.execute(context.getSandbox(), context.getContainerRepository(), List.of("git", "status", "--porcelain"), context.getTimeout());
        stdout.addAll(statusResult.getStandardOutput());
        stderr.addAll(statusResult.getStandardError());
        boolean clean = statusResult.getExitCode() == 0 && statusResult.getStandardOutput().isEmpty();

        Map<String, Object> resultData = Map.of(
            "branch", branch,
            "headCommit", headCommit,
            "workingTreeClean", clean
        );
        return new ToolResult(0, resultData, stdout, stderr);
    }

    private ToolResult executeGitPush(ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        String expectedHeadCommit = ToolArguments.string(arguments, "expectedHeadCommit", 64);
        String remote = ToolArguments.string(arguments, "remote", 64);
        String branch = ToolArguments.string(arguments, "branch", 256);

        if (!"origin".equals(remote)) {
            return new ToolResult(1, Map.of("error", "Remote must be origin"), List.of(), List.of("Remote must be origin"));
        }
        if (branch == null || branch.contains(" ") || branch.contains("--") || branch.contains("..") || branch.startsWith("-")) {
            return new ToolResult(1, Map.of("error", "Invalid branch name format"), List.of(), List.of("Invalid branch name format"));
        }

        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();

        CommandExecutionResult branchResult = executor.execute(context.getSandbox(), context.getContainerRepository(),
                List.of("git", "symbolic-ref", "--short", "HEAD"), context.getTimeout());
        stdout.addAll(branchResult.getStandardOutput());
        stderr.addAll(branchResult.getStandardError());
        if (branchResult.getExitCode() != 0 || branchResult.getStandardOutput().isEmpty()
                || !branch.equals(branchResult.getStandardOutput().get(0).trim())) {
            return new ToolResult(1, Map.of("pushed", false, "reason", "BRANCH_MISMATCH", "branch", branch), stdout, stderr);
        }

        CommandExecutionResult shaResult = executor.execute(context.getSandbox(), context.getContainerRepository(), List.of("git", "rev-parse", "HEAD"), context.getTimeout());
        stdout.addAll(shaResult.getStandardOutput());
        stderr.addAll(shaResult.getStandardError());

        if (shaResult.getExitCode() != 0) {
            return new ToolResult(shaResult.getExitCode(), Map.of("pushed", false, "reason", "FAILED_TO_RESOLVE_HEAD"), stdout, stderr);
        }

        String headCommit = shaResult.getStandardOutput().isEmpty() ? "" : shaResult.getStandardOutput().get(0).trim();
        if (!headCommit.equals(expectedHeadCommit)) {
            return new ToolResult(1, Map.of("pushed", false, "reason", "HEAD_MISMATCH", "headCommit", headCommit, "branch", branch), stdout, stderr);
        }

        CommandExecutionResult pushResult = executor.execute(context.getSandbox(), context.getContainerRepository(),
                List.of("git", "push", remote, "HEAD:refs/heads/" + branch), context.getTimeout());
        stdout.addAll(pushResult.getStandardOutput());
        stderr.addAll(pushResult.getStandardError());

        if (pushResult.getExitCode() != 0) {
            return new ToolResult(pushResult.getExitCode(), Map.of("pushed", false, "reason", "PUSH_FAILED", "headCommit", headCommit, "branch", branch), stdout, stderr);
        }

        // Verify remote
        CommandExecutionResult lsRemoteResult = executor.execute(context.getSandbox(), context.getContainerRepository(), List.of("git", "ls-remote", remote, "refs/heads/" + branch), context.getTimeout());
        stdout.addAll(lsRemoteResult.getStandardOutput());
        stderr.addAll(lsRemoteResult.getStandardError());

        if (lsRemoteResult.getExitCode() != 0 || lsRemoteResult.getStandardOutput().isEmpty()) {
            return new ToolResult(1, Map.of("pushed", false, "reason", "REMOTE_VERIFICATION_FAILED", "headCommit", headCommit, "branch", branch), stdout, stderr);
        }

        String remoteLine = lsRemoteResult.getStandardOutput().get(0).trim();
        String remoteSha = remoteLine.split("\\s+")[0];

        if (!remoteSha.equals(headCommit)) {
            return new ToolResult(1, Map.of("pushed", false, "reason", "REMOTE_SHA_MISMATCH", "headCommit", headCommit, "branch", branch), stdout, stderr);
        }

        return new ToolResult(0, Map.of("pushed", true, "headCommit", headCommit, "branch", branch), stdout, stderr);
    }

    private List<String> command(String name, Map<String, Object> arguments) {
        return switch (name) {
            case "git.status" -> List.of("git", "status", "--short", "--branch");
            case "git.diff" -> gitDiff(arguments);
            case "git.log" -> List.of("git", "log", "--oneline", "--decorate", "-n",
                    Integer.toString(ToolArguments.integer(arguments, "limit", 20, 1, 100)));
            case "git.add" -> withPaths(List.of("git", "add", "--"), arguments);
            case "git.commit" -> List.of("git", "commit", "-m", ToolArguments.string(arguments, "message", 500));
            default -> throw new IllegalArgumentException("不支持的 Git 工具：" + name);
        };
    }

    private List<String> gitDiff(Map<String, Object> arguments) {
        List<String> command = new ArrayList<>(List.of("git", "diff", "--no-ext-diff", "--no-color"));
        if (Boolean.TRUE.equals(arguments.get("staged"))) {
            command.add("--cached");
        }
        return List.copyOf(command);
    }

    private List<String> withPaths(List<String> prefix, Map<String, Object> arguments) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(ToolArguments.strings(arguments, "paths", 256, 1024));
        return List.copyOf(command);
    }
}
