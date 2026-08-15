package qg.qgent.orchestration.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.service.WorkspaceService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@link ExecutionPort} 的本地受限实现（接入 Sandbox 的真实执行入口）：
 * 命令白名单校验 → {@link WorkspaceService} 定位工作区根目录 → 工作区内执行。
 * <p>
 * 安全边界（本地受限执行器，非生产级安全 Sandbox）：
 * <ul>
 *   <li>命令必须命中 {@link SandboxCommandPolicy} 白名单，否则直接拒绝且不启动任何进程；</li>
 *   <li>工作区根目录一律由 {@link WorkspaceService#resolve} 解析，本类不自行拼接或解析路径，防止越界；</li>
 *   <li>进程 cwd 固定为工作区根目录，stdout/stderr/exitCode 由 {@link SandboxProcessRunner} 真实捕获；</li>
 *   <li>Workspace 不存在/未就绪返回明确失败；WorkspaceService 的 Mapper 异常向上传播，不吞异常。</li>
 * </ul>
 * {@code @Primary} 使本实现成为 TestAgent 等注入点的默认 ExecutionPort；{@link DisabledExecutionPort}
 * 保留用于单元测试 / 未启用场景（需要时可用 {@code @Qualifier} 显式选择）。
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "false", matchIfMissing = true)
public class LocalSandboxExecutionPort implements ExecutionPort {

    private final WorkspaceService workspaceService;
    private final SandboxCommandPolicy commandPolicy = new SandboxCommandPolicy();
    private final SandboxProcessRunner processRunner;

    @Autowired
    public LocalSandboxExecutionPort(WorkspaceService workspaceService) {
        this(workspaceService, new SandboxProcessRunner());
    }

    /**
     * 测试用构造：可注入 Runner 桩，白名单仍为真实策略。
     */
    LocalSandboxExecutionPort(WorkspaceService workspaceService, SandboxProcessRunner processRunner) {
        this.workspaceService = workspaceService;
        this.processRunner = processRunner;
    }

    @Override
    public ExecutionResult execute(UUID workspaceId, List<String> command, Duration timeout) {
        if (!commandPolicy.allows(command)) {
            return new ExecutionResult(false, -1, "", "", "command not allowed by sandbox policy: " + command);
        }
        WorkspaceService.WorkspaceResolution resolution = workspaceService.resolve(workspaceId);
        if (!resolution.ready() || resolution.root() == null) {
            return new ExecutionResult(false, -1, "", "", "workspace is not ready for execution: "
                    + (resolution.reason() == null ? "unknown" : resolution.reason()));
        }
        return processRunner.run(resolution.root(), wrapForLaunch(command), timeout);
    }

    /**
     * 按平台构造实际进程启动向量：Windows 下 mvn/npm/gradle 的批处理入口需经 cmd.exe 启动，
     * 其余平台原样返回。白名单已在调用本方法前对原始命令完成校验。
     */
    static List<String> wrapForLaunch(List<String> command) {
        if (!isWindows()) {
            return command;
        }
        List<String> argv = new ArrayList<>(command.size() + 2);
        argv.add("cmd.exe");
        argv.add("/c");
        argv.addAll(command);
        return List.copyOf(argv);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    }
}
