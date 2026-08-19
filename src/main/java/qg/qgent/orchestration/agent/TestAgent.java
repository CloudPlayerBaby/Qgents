package qg.qgent.orchestration.agent;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.ExecutionResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 真实 Test Agent：依据工作区构建工具解析安全测试命令，通过 {@link ExecutionPort} 在
 * Sandbox 内真实执行，收集 exitCode/stdout/stderr，交由 LLM 分析并产出结构化 {@link TestResult}。
 * <p>
 * 真实验证约束：
 * <ul>
 *   <li>success 以 ExecutionPort 返回的真实 exit code 为准（exitCode==0 才通过），LLM 不得推翻；</li>
 *   <li>LLM 不参与命令选择，命令由 {@link TestCommandResolver} 白名单模板解析；</li>
 *   <li>检测不到受支持构建工具时，纯文件任务走只读文件断言；无法确定断言目标时才判 Task FAILED；</li>
 *   <li>ExecutionPort 返回 ok=false（Sandbox 未就绪等）→ FAILED_INFRASTRUCTURE 同相位重试；</li>
 *   <li>LLM 分析失败仅退回基于真实执行的结果，不影响 PASS/FAIL 真实性。</li>
 * </ul>
 * 不修改 Workspace、不 write_file、不调用其他 Agent、不执行 Git 命令、不访问宿主机。
 */
@Component
@Slf4j
public class TestAgent implements Agent {

    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);
    private static final String EMPTY_FILE_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * 视为“非代码文件”的文件扩展名集合：这些文件的修改不涉及可构建/可测试的源码，
     * 因此无需运行 gradle/mvn/npm 测试，直接做文件断言即可。
     * 判断时按小写扩展名匹配（含常见文档、配置、资源、数据与目录占位文件）。
     */
    private static final Set<String> NON_CODE_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "log", "rst", "adoc",
            "json", "yaml", "yml", "toml", "xml", "properties", "ini", "conf", "cfg",
            "csv", "tsv", "sql",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "tar", "gz", "jar",
            "css", "scss", "less", "html", "htm",
            "sh", "bash", "zsh", "bat", "cmd", "ps1",
            "gradle", "kts", "pom", "lock", "gitignore", "gitkeep", "dockerfile", "env", "editorconfig");

    /** 无扩展名的文件（如 README、LICENSE、Makefile、Dockerfile）同样视为非代码文件。 */
    private static final Set<String> NON_CODE_BASENAMES = Set.of(
            "readme", "license", "makefile", "dockerfile", "gemfile", "rakefile");

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final ExecutionPort executionPort;
    private final TestCommandResolver commandResolver = new TestCommandResolver();
    private final TestPromptBuilder promptBuilder = new TestPromptBuilder();
    private final TestResultParser parser = new TestResultParser();

    public TestAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, ExecutionPort executionPort) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.executionPort = executionPort;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        try {
            List<String> files = codeAccess.listFiles(input.getWorkspaceId());
            if (isManualVerification(input)) {
                return manualVerification(input);
            }
            if (isPureFileTask(input)) {
                return verifyFileTask(input, files);
            }
            TestCommandResolver.ResolvedCommand resolved = commandResolver.resolveCommand(files, fileTargets(input));
            if (resolved == null) {
                return verifyFileTask(input, files);
            }
            List<String> command = resolved.command();
            ExecutionResult exec = resolved.repositoryPath() == null
                    ? executionPort.execute(input.getWorkspaceId(), command, TEST_TIMEOUT)
                    : executionPort.execute(input.getWorkspaceId(), resolved.repositoryPath(), command, TEST_TIMEOUT);
            if (!exec.ok()) {
                return infraFailure(input, exec.error() == null ? "test execution unavailable"
                        : ExecutionContentSanitizer.sanitize(exec.error()));
            }
            ExecutionResult safeExec = sanitizedAndLimited(exec);
            if (isCommandUnavailable(safeExec)) {
                return infraFailure(input, "build environment unavailable (exit code " + exec.exitCode()
                        + "): selected wrapper or build tool could not be launched; use a workspace-relative wrapper such as ./gradlew or ./mvnw");
            }
            TestResult test = analyze(input, command, safeExec);
            boolean passed = exec.exitCode() == 0;
            test.setSuccess(passed);
            test.setVerificationMode("COMMAND");
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setTestResult(test);
            outcome.setOutcome(passed ? RunOutcome.SUCCEEDED
                    : (test.isNeedsCodingFix() ? RunOutcome.FAILED_QUALITY : RunOutcome.FAILED));
            outcome.setMessage(test.getSummary());
            return outcome;
        } catch (RuntimeException e) {
            return infraFailure(input, e.getMessage());
        }
    }

    /**
     * 由 LLM 分析真实输出；分析失败或非法时退回基于真实执行的结果，不伪造分析。
     */
    private TestResult analyze(AgentInput input, List<String> command, ExecutionResult exec) {
        String system = promptBuilder.buildSystem();
        try {
            String raw = llm.complete(system,
                    List.of(LlmMessage.user(promptBuilder.buildUser(input, command, exec))));
            TestResult test;
            try {
                test = parser.parse(raw);
            } catch (TestParseException malformed) {
                String repaired = JsonRepairSupport.repairOnce(llm, system, raw, malformed.getMessage(),
                        "{\"success\":true|false,\"summary\":\"测试摘要\","
                                + "\"failures\":[{\"name\":\"测试项\",\"reason\":\"原因\","
                                + "\"severity\":\"ERROR|WARNING|INFO\"}],\"needsCodingFix\":true|false}");
                if (repaired == null) {
                    throw malformed;
                }
                test = parser.parse(repaired);
            }
            test.setExitCode(exec.exitCode());
            test.setCommand(String.join(" ", command));
            test.setStdout(exec.stdout());
            test.setStderr(exec.stderr());
            return test;
        } catch (RuntimeException e) {
            return fallback(exec, command, e.getMessage());
        }
    }

    private TestResult fallback(ExecutionResult exec, List<String> command, String analysisError) {
        TestResult test = new TestResult();
        test.setExitCode(exec.exitCode());
        test.setCommand(String.join(" ", command));
        test.setStdout(exec.stdout());
        test.setStderr(exec.stderr());
        test.setSummary("测试已执行，LLM 分析失败：" + ExecutionContentSanitizer.sanitize(analysisError));
        if (exec.exitCode() != 0) {
            TestResult.Failure failure = new TestResult.Failure();
            failure.setName("test execution");
            failure.setReason("exit code " + exec.exitCode() + "；分析失败无法给出具体失败项");
            failure.setSeverity("ERROR");
            test.setFailures(List.of(failure));
            test.setNeedsCodingFix(true);
        }
        return test;
    }

    private AgentRunOutcome noTestCommand(AgentInput input) {
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setExitCode(-1);
        test.setVerificationMode("NONE");
        test.setSummary("未检测到受支持的项目/测试命令，未执行测试");
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName("no testable build tool");
        failure.setReason("工作区未检测到 pom.xml / build.gradle / package.json 之一，无法确定安全测试命令");
        failure.setSeverity("ERROR");
        test.setFailures(List.of(failure));

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setOutcome(RunOutcome.FAILED);
        outcome.setTestResult(test);
        outcome.setMessage(test.getSummary());
        return outcome;
    }

    /**
     * 没有 Maven/Gradle/npm 等构建入口时，对 Coding 明确涉及的文件执行确定性的基础断言。
     * 这条路径不调用 LLM，也不执行任意命令，适用于“清空文件”“创建/修改配置文件”等纯文件任务。
     */
    private AgentRunOutcome verifyFileTask(AgentInput input, List<String> files) {
        List<String> targets = fileTargets(input);
        List<String> directories = directoryTargets(input);
        if (targets.isEmpty() && directories.isEmpty()) {
            if (isManualVerification(input)) {
                return manualVerification(input);
            }
            return noTestCommand(input);
        }

        Set<String> available = (files == null ? List.<String>of() : files).stream()
                .map(this::normalizePath)
                .filter(path -> !path.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean requireEmpty = requiresEmptyFile(input);
        List<TestResult.Failure> failures = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        for (String target : targets) {
            // listFiles intentionally omits dot-files from the normal context tree. Read
            // hidden targets directly, but retain the fast existence guard for ordinary files.
            if (!available.contains(target) && !isHiddenFile(target)) {
                failures.add(failure(target, "目标文件不存在", "ERROR"));
                continue;
            }
            WorkspaceFileReadResult read = codeAccess.readFile(input.getWorkspaceId(), target);
            if (read == null || !read.isOk()) {
                failures.add(failure(target, read == null || read.getError() == null
                        ? "目标文件不可读取" : read.getError(), "ERROR"));
                continue;
            }
            String content = read.getContent() == null ? "" : read.getContent();
            if (requireEmpty && !isEmptyFile(read, content)) {
                failures.add(failure(target, "任务要求文件为空，但当前仍有内容", "ERROR"));
                continue;
            }
            checks.add(requireEmpty ? target + "(0 bytes)" : target + "(exists/readable)");
        }
        for (String directory : directories) {
            boolean representedByFile = available.stream().anyMatch(path -> isUnderDirectory(path, directory))
                    || targets.stream().anyMatch(path -> isUnderDirectory(path, directory));
            if (representedByFile) {
                checks.add(directory + "(exists)");
            } else {
                // create_directory has already returned changed=true and the path was
                // recorded by CodingAgent's server-side write ledger. Empty directories
                // are intentionally absent from Git/file.list, so there is no file to read.
                checks.add(directory + "(created)");
            }
        }

        TestResult test = new TestResult();
        test.setVerificationMode("FILE_ASSERTION");
        test.setCommand("file assertions");
        test.setExitCode(failures.isEmpty() ? 0 : 1);
        test.setFailures(failures);
        test.setNeedsCodingFix(!failures.isEmpty());
        test.setSuccess(failures.isEmpty());
        test.setSummary(failures.isEmpty()
                ? "未检测到项目测试命令，已完成文件断言：" + String.join(", ", checks)
                : "文件断言未通过：" + failures.get(0).getReason());

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setTestResult(test);
        outcome.setOutcome(failures.isEmpty() ? RunOutcome.SUCCEEDED : RunOutcome.FAILED_QUALITY);
        outcome.setMessage(test.getSummary());
        return outcome;
    }

    private boolean isManualVerification(AgentInput input) {
        return input.getPlanResult() != null
                && "MANUAL".equalsIgnoreCase(input.getPlanResult().getVerificationMode());
    }

    /**
     * Pure review tasks have no build command by design. Their evidence is the
     * structured Developer report, not a fabricated shell test.
     */
    private AgentRunOutcome manualVerification(AgentInput input) {
        TestResult test = new TestResult();
        boolean hasReport = input.getCodingResult() != null
                && input.getCodingResult().getSummary() != null
                && !input.getCodingResult().getSummary().isBlank();
        test.setVerificationMode("MANUAL");
        test.setSuccess(hasReport);
        test.setExitCode(hasReport ? 0 : -1);
        test.setSummary(hasReport
                ? "纯审查任务已按测试计划完成人工验收"
                : "纯审查任务缺少 Developer 检查报告，无法完成人工验收");
        if (!hasReport) {
            test.setFailures(List.of(failure("missing inspection report",
                    "Developer 未产出可供验收的检查报告", "ERROR")));
        }

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setTestResult(test);
        outcome.setOutcome(hasReport ? RunOutcome.SUCCEEDED : RunOutcome.FAILED_QUALITY);
        outcome.setMessage(test.getSummary());
        return outcome;
    }

    /**
     * 判断是否为纯文件任务：Coding 修改的所有文件都是非代码文件（文档/配置/资源/目录等）。
     * 此时无需运行 gradle/mvn/npm 测试，直接做文件断言即可完成验证；
     * 避免“往 README 写一行字”这种简单任务被迫跑整个项目的构建测试而失败。
     */
    private boolean isPureFileTask(AgentInput input) {
        List<String> targets = fileTargets(input);
        List<String> directories = directoryTargets(input);
        if (targets.isEmpty() && directories.isEmpty()) {
            return false;
        }
        return targets.stream().allMatch(this::isNonCodeFile);
    }

    /**
     * 判断路径是否指向非代码文件：按扩展名或 basename 匹配，忽略目录分隔符与大小写。
     */
    private boolean isNonCodeFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String basename = slash < 0 ? normalized : normalized.substring(slash + 1);
        int dot = basename.lastIndexOf('.');
        if (dot < 0) {
            return NON_CODE_BASENAMES.contains(basename.toLowerCase(Locale.ROOT));
        }
        String extension = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return NON_CODE_EXTENSIONS.contains(extension);
    }

    /** Coding 结果是纯文件验证的可信目标来源；不从聊天历史猜测文件路径。 */
    private List<String> fileTargets(AgentInput input) {
        if (input.getCodingResult() == null || input.getCodingResult().getModifiedFiles() == null) {
            return List.of();
        }
        return input.getCodingResult().getModifiedFiles().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(this::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    /** Coding 产生的真实新建目录；目录本身没有文件扩展名，始终按纯文件变更处理。 */
    private List<String> directoryTargets(AgentInput input) {
        if (input.getCodingResult() == null || input.getCodingResult().getModifiedDirectories() == null) {
            return List.of();
        }
        return input.getCodingResult().getModifiedDirectories().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(this::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    private boolean isUnderDirectory(String path, String directory) {
        if (path == null || directory == null || path.isBlank() || directory.isBlank()) {
            return false;
        }
        String normalizedPath = normalizePath(path);
        String normalizedDirectory = normalizePath(directory);
        return normalizedPath.equals(normalizedDirectory)
                || normalizedPath.startsWith(normalizedDirectory + "/");
    }

    private boolean isHiddenFile(String path) {
        String normalized = normalizePath(path);
        for (String segment : normalized.split("/")) {
            if (!segment.isBlank() && !segment.equals(".") && !segment.equals("..")
                    && segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** 统一 Worker/local 实现返回的相对路径，避免 ./ 与反斜杠导致验证误判。 */
    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.replaceFirst("/+$", "");
    }

    /** 只对明确表达“清空/置空/零字节”的需求执行内容为空断言。 */
    private boolean requiresEmptyFile(AgentInput input) {
        String text = String.join(" ",
                safe(input.getRequirement()), safe(input.getInstruction()),
                input.getPlanResult() == null ? "" : safe(input.getPlanResult().getTestPlan()));
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return text.contains("清空") || text.contains("置空") || text.contains("清除内容")
                || text.contains("内容为空") || lower.contains("empty file")
                || lower.contains("zero-byte") || lower.contains("truncate");
    }

    private TestResult.Failure failure(String name, String reason, String severity) {
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName(name);
        failure.setReason(reason);
        failure.setSeverity(severity);
        return failure;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 命令找不到或无执行权限属于 Sandbox 环境问题，不应让 Coding Agent 修改业务代码。 */
    private boolean isCommandUnavailable(ExecutionResult exec) {
        if (exec.exitCode() == 126 || exec.exitCode() == 127) {
            return true;
        }
        String output = (safe(exec.stdout()) + "\n" + safe(exec.stderr())).toLowerCase(java.util.Locale.ROOT);
        return output.contains("command not found")
                || output.contains("not found in $path")
                || output.contains("no such file or directory")
                || output.contains("cannot execute");
    }

    /** 优先使用 Worker 返回的原始字节哈希，避免按行读取丢失末尾换行导致误判 0 字节。 */
    private boolean isEmptyFile(WorkspaceFileReadResult read, String content) {
        String sha = read.getSha256();
        if (sha != null && !sha.isBlank()) {
            return EMPTY_FILE_SHA256.equalsIgnoreCase(sha.replaceFirst("^sha256:", ""));
        }
        return content.getBytes(StandardCharsets.UTF_8).length == 0;
    }

    private AgentRunOutcome infraFailure(AgentInput input, String message) {
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setPhase(input.getPhase());
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        String safeMessage = ExecutionContentSanitizer.sanitize(message);
        String code = infrastructureCode(safeMessage);
        failure.setFailureCode(code);
        failure.setMessage("test agent failed: " + safeMessage);
        log.warn("tester infrastructure failure workspaceId={} failureCode={}", input.getWorkspaceId(), code);
        return failure;
    }

    private String infrastructureCode(String message) {
        String value = message == null ? "" : message.toUpperCase(java.util.Locale.ROOT);
        if (value.contains("SANDBOX_WORKER_UNAVAILABLE")) return "SANDBOX_WORKER_UNAVAILABLE";
        if (value.contains("WORKSPACE_WRITE_LEASE_LOST")) return "WORKSPACE_WRITE_LEASE_LOST";
        if (value.contains("SANDBOX_NOT_FOUND")) return "SANDBOX_NOT_FOUND";
        if (value.contains("DOCKER_EXEC") || value.contains("DOCKER_ENGINE")) return "DOCKER_EXEC_FAILED";
        if (value.contains("BUILD ENVIRONMENT UNAVAILABLE")) return "BUILD_ENVIRONMENT_UNAVAILABLE";
        if (value.contains("TIMEOUT") || value.contains("TIMED OUT") || value.contains("超时")) return "TEST_EXECUTION_TIMEOUT";
        return "FAILED_INFRASTRUCTURE";
    }

    private ExecutionResult sanitizedAndLimited(ExecutionResult exec) {
        String stdout = PromptTextLimiter.limitHeadTail(ExecutionContentSanitizer.sanitize(exec.stdout()),
                TestPromptBuilder.MAX_STDOUT_CHARS);
        String stderr = PromptTextLimiter.limitHeadTail(ExecutionContentSanitizer.sanitize(exec.stderr()),
                TestPromptBuilder.MAX_STDERR_CHARS);
        return new ExecutionResult(exec.ok(), exec.exitCode(), stdout, stderr,
                ExecutionContentSanitizer.sanitize(exec.error()));
    }
}
