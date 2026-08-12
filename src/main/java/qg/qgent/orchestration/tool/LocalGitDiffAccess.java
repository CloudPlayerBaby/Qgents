package qg.qgent.orchestration.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * {@link WorkspaceDiffAccess} 的真实本地实现：在工作区内的每个 Repository worktree 上执行
 * 只读 {@code git diff}，聚合后返回 {@link GitDiffResult}，供 Review Agent 审查 Coding 改动。
 * <p>
 * 复用已有能力，不重复设计 Git 服务：
 * <ul>
 *   <li>Workspace 根目录一律由 {@link WorkspaceService#resolve} 定位（app.workspace.base-dir/{storageKey}），
 *       本类不自行解析或拼接工作区路径；</li>
 *   <li>Repository worktree 由 {@link WorkspaceRepositoryMapper#selectByWorkspace} 读取（workspacePath 为
 *       相对 worktree 名），再基于工作区根目录解析，禁止越界；</li>
 *   <li>git 进程由同包 {@link SandboxProcessRunner} 启动，argv 由 {@link GitDiffCommandBuilder} 构造并校验
 *       base ref，本类不执行 commit/push/MR 等任何 Git 写操作；</li>
 *   <li>base 取 headCommit（非空）否则 baseCommit，二者均为真实 Git ref，经 rev-parse 解析为真实 SHA，
 *       不伪造 base/head commit。</li>
 * </ul>
 * 失败语义（与 ExecutionPort 端口一致）：Workspace 未就绪 / 无 worktree / 路径越界 / git 命令失败均返回
 * ok=false 与明确原因；Mapper 或文件系统异常向上传播，由 Review Agent 映射为 FAILED_INFRASTRUCTURE，
 * 不吞异常、不伪装成功。
 * <p>
 * {@code @Primary} 使本实现成为默认注入点；{@link DisabledWorkspaceDiffAccess} 保留用于单元测试 /
 * 未启用场景（需要时可用 {@code @Qualifier} 显式选择）。
 */
@Component
@Primary
public class LocalGitDiffAccess implements WorkspaceDiffAccess {

    /** 单次 git 命令超时。 */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final WorkspaceService workspaceService;
    private final WorkspaceRepositoryMapper repositoryMapper;
    private final SandboxProcessRunner processRunner;

    @Autowired
    public LocalGitDiffAccess(WorkspaceService workspaceService, WorkspaceRepositoryMapper repositoryMapper) {
        this(workspaceService, repositoryMapper, new SandboxProcessRunner());
    }

    /** 测试用构造：可注入 Runner 桩，Workspace 定位与 worktree 查询仍为真实依赖。 */
    LocalGitDiffAccess(WorkspaceService workspaceService, WorkspaceRepositoryMapper repositoryMapper,
            SandboxProcessRunner processRunner) {
        this.workspaceService = workspaceService;
        this.repositoryMapper = repositoryMapper;
        this.processRunner = processRunner;
    }

    @Override
    public GitDiffResult diff(UUID workspaceId) {
        WorkspaceService.WorkspaceResolution resolution = workspaceService.resolve(workspaceId);
        if (!resolution.ready() || resolution.root() == null) {
            return failure("workspace is not ready for git diff: "
                    + (resolution.reason() == null ? "unknown" : resolution.reason()));
        }
        Path root = resolution.root();
        List<WorkspaceRepositoryEntity> worktrees = repositoryMapper.selectByWorkspace(workspaceId);
        if (worktrees == null || worktrees.isEmpty()) {
            return failure("workspace has no repository worktrees");
        }
        StringBuilder combined = new StringBuilder();
        String baseCommit = null;
        String headCommit = null;
        for (WorkspaceRepositoryEntity worktree : worktrees) {
            WorktreeDiff worktreeDiff = diffWorktree(root, worktree);
            if (!worktreeDiff.ok()) {
                return failure(worktreeDiff.error());
            }
            if (baseCommit == null) {
                baseCommit = worktreeDiff.baseCommit();
                headCommit = worktreeDiff.headCommit();
            }
            if (!worktreeDiff.diff().isBlank()) {
                if (combined.length() > 0) {
                    combined.append('\n');
                }
                combined.append("===== ").append(worktree.getWorkspacePath()).append(" =====\n")
                        .append(worktreeDiff.diff());
            }
        }
        return GitDiffResult.ok(combined.toString(), baseCommit, headCommit);
    }

    /** 在单个 worktree 上执行 git diff + rev-parse，返回 diff 文本与真实 base/head SHA。 */
    private WorktreeDiff diffWorktree(Path root, WorkspaceRepositoryEntity worktree) {
        String workspacePath = worktree.getWorkspacePath();
        Path repoDir = resolveWorktree(root, workspacePath);
        if (repoDir == null) {
            return WorktreeDiff.failure("worktree path escapes workspace root: " + workspacePath);
        }
        String base = baseRef(worktree);
        if (!GitDiffCommandBuilder.isValidBase(base)) {
            return WorktreeDiff.failure("worktree has no valid base ref");
        }
        if (!Files.isDirectory(repoDir)) {
            return WorktreeDiff.failure("worktree directory not present: " + repoDir);
        }
        ExecutionResult diff = processRunner.run(repoDir, GitDiffCommandBuilder.diffCommand(base), GIT_TIMEOUT);
        if (!diff.ok()) {
            return WorktreeDiff.failure("git diff failed to run: " + diff.error());
        }
        if (diff.exitCode() != 0) {
            return WorktreeDiff.failure("git diff failed (exit " + diff.exitCode() + "): " + firstLine(diff.stderr()));
        }
        ExecutionResult baseRev = processRunner.run(repoDir, GitDiffCommandBuilder.revParseCommand(base), GIT_TIMEOUT);
        ExecutionResult headRev = processRunner.run(repoDir, GitDiffCommandBuilder.revParseCommand("HEAD"), GIT_TIMEOUT);
        if (!baseRev.ok() || baseRev.exitCode() != 0 || !headRev.ok() || headRev.exitCode() != 0) {
            return WorktreeDiff.failure("git rev-parse failed to resolve base/head");
        }
        String baseSha = baseRev.stdout().trim();
        String headSha = headRev.stdout().trim();
        if (!GitDiffCommandBuilder.isValidBase(baseSha) || !GitDiffCommandBuilder.isValidBase(headSha)) {
            return WorktreeDiff.failure("git rev-parse returned invalid commit");
        }
        return WorktreeDiff.ok(diff.stdout(), baseSha, headSha);
    }

    /** diff base：优先已接受的 headCommit，否则 worktree 创建时的 baseCommit。 */
    private static String baseRef(WorkspaceRepositoryEntity worktree) {
        String head = worktree.getHeadCommit();
        return (head != null && !head.isBlank()) ? head : worktree.getBaseCommit();
    }

    /** 把 DB 中的相对 worktree 名解析到工作区根目录下，越界/非法时返回 null。 */
    private static Path resolveWorktree(Path root, String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return null;
        }
        Path repoDir = root.resolve(workspacePath).normalize();
        return repoDir.startsWith(root) ? repoDir : null;
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }

    private static GitDiffResult failure(String error) {
        return new GitDiffResult(false, "", "", "", error);
    }

    /** 单个 worktree 的 diff 结果（内部值对象）。 */
    private record WorktreeDiff(boolean ok, String diff, String baseCommit, String headCommit, String error) {

        static WorktreeDiff ok(String diff, String baseCommit, String headCommit) {
            return new WorktreeDiff(true, diff, baseCommit, headCommit, null);
        }

        static WorktreeDiff failure(String error) {
            return new WorktreeDiff(false, "", "", "", error);
        }
    }
}
