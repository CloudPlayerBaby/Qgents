package qg.qgent.orchestration.agent;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.PlanResult;
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
import java.util.UUID;

/**
 * 真实 Test Agent：依据工作区构建工具解析安全测试命令，通过 {@link ExecutionPort} 在
 * Sandbox 内真实执行，收集 exitCode/stdout/stderr，交由 LLM 分析并产出结构化 {@link TestResult}。
 * <p>
 * 真实验证约束：
 * <ul>
 *   <li>success 以 ExecutionPort 返回的真实 exit code 为准（exitCode==0 才通过），LLM 不得推翻；</li>
 *   <li>LLM 不参与命令选择，命令由 {@link TestCommandResolver} 白名单模板解析；</li>
 *   <li>检测不到受支持构建工具时，纯文件任务走只读文件断言；</li>
 *   <li>ExecutionPort 返回 ok=false（Sandbox 未就绪等）→ FAILED_INFRASTRUCTURE 同相位重试；</li>
 *   <li><b>Test 不判定任务是否失败</b>：一切测试执行失败（代码缺陷/环境/超时/未检测到命令/文件断言/
 *       缺人工验收报告）统一 {@link RunOutcome#TEST_FAILED} 携带完整失败信息转交 Review 裁决——
 *       Review 独立审查代码逻辑，仅 BLOCKER/MAJOR 判失败并打回 Coding，否则放行；</li>
 *   <li>{@link TestFailureClassifier} 只在 LLM 分析前为环境类失败打上 environmentFailureCode 元数据
 *       （决定能否打回 Coding 与终态标注），不再决定失败路由；</li>
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
    private final TestFailureClassifier failureClassifier = new TestFailureClassifier();

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
            // Plan/Step 冻结的按仓库验证命令优先：恢复续跑时 planResult 为 null，但 TaskStep
            // 持久化的 verificationCommands 仍可用。全部命令非法时返回 null，回退自动探测。
            if (input.getVerificationCommands() != null && !input.getVerificationCommands().isEmpty()) {
                AgentRunOutcome planned = executePlannedVerification(input);
                if (planned != null) {
                    return planned;
                }
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
                return environmentBlocked(input, command, safeExec, "BUILD_ENVIRONMENT_UNAVAILABLE");
            }
            // 环境/超时/依赖网络失败在 LLM 分析之前分流：只打环境失败码元数据（决定终态标注与能否
            // 打回 Coding），不再判定失败去向——所有测试失败统一 TEST_FAILED 交 Review 裁决。
            // 只对真实失败（exit != 0）分类，避免通过执行的日志误触发环境关键字。
            if (exec.exitCode() != 0) {
                TestFailureClassifier.Verdict verdict = failureClassifier.classify(
                        exec.exitCode(), exec.stdout(), exec.stderr(), fileTargets(input));
                if (verdict.classification() == TestFailureClassifier.Classification.ENVIRONMENT) {
                    // 超时不再判任务失败：交 Review 独立裁决（代码有 MAJOR+ 缺陷仍可打回 Coding）。
                    if ("TEST_EXECUTION_TIMEOUT".equals(verdict.failureCode())) {
                        return testExecutionTimeout(input, command, safeExec);
                    }
                    return environmentBlocked(input, command, safeExec, verdict.failureCode());
                }
            }
            TestResult test = analyze(input, command, safeExec);
            boolean passed = exec.exitCode() == 0;
            test.setSuccess(passed);
            test.setVerificationMode("COMMAND");
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setTestResult(test);
            // Test 不自行判定任务失败：非零退出一律 TEST_FAILED 交 Review 裁决（仅 MAJOR+ 判失败）。
            outcome.setOutcome(passed ? RunOutcome.SUCCEEDED : RunOutcome.TEST_FAILED);
            outcome.setMessage(test.getSummary());
            // 非零退出码是已验证的执行事实，不能让后续产物/诊断退化为 failureCode=null。
            if (!passed) {
                outcome.setFailureCode("PROCESS_EXIT_NONZERO");
            }
            return outcome;
        } catch (RuntimeException e) {
            return infraFailure(input, e.getMessage());
        }
    }

    /**
     * 按 TaskStep 冻结的按仓库验证命令逐一执行（白名单防御性再校验），聚合结果后交由 LLM 分析。
     * 任一命令失败 → 整体失败；全部通过 → 成功。无合法命令（旧数据或全部被过滤）返回 null，
     * 由调用方回退自动探测。
     */
    private AgentRunOutcome executePlannedVerification(AgentInput input) {
        List<TaskStepEntity.VerificationCommand> allowed = input.getVerificationCommands().stream()
                .filter(command -> command != null && command.getCommand() != null
                        && TestCommandResolver.isAllowedVerificationCommand(command.getCommand()))
                .toList();
        if (allowed.isEmpty()) {
            return null;
        }
        List<String> commandTexts = new ArrayList<>();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int worstExit = 0;
        List<TestResult.Failure> failures = new ArrayList<>();
        for (TaskStepEntity.VerificationCommand entry : allowed) {
            List<String> command = entry.getCommand();
            String repositoryPath = blankToNull(entry.getRepositoryPath());
            ExecutionResult exec = repositoryPath == null
                    ? executionPort.execute(input.getWorkspaceId(), command, TEST_TIMEOUT)
                    : executionPort.execute(input.getWorkspaceId(), repositoryPath, command, TEST_TIMEOUT);
            if (!exec.ok()) {
                return infraFailure(input, exec.error() == null ? "test execution unavailable"
                        : ExecutionContentSanitizer.sanitize(exec.error()));
            }
            ExecutionResult safeExec = sanitizedAndLimited(exec);
            if (isCommandUnavailable(safeExec)) {
                return environmentBlocked(input, command, safeExec, "BUILD_ENVIRONMENT_UNAVAILABLE");
            }
            if (exec.exitCode() != 0) {
                TestFailureClassifier.Verdict verdict = failureClassifier.classify(
                        exec.exitCode(), exec.stdout(), exec.stderr(), fileTargets(input));
                if (verdict.classification() == TestFailureClassifier.Classification.ENVIRONMENT) {
                    if ("TEST_EXECUTION_TIMEOUT".equals(verdict.failureCode())) {
                        return testExecutionTimeout(input, entry.getCommand(), safeExec);
                    }
                    return environmentBlocked(input, command, safeExec, verdict.failureCode());
                }
                worstExit = exec.exitCode();
                TestResult.Failure failure = new TestResult.Failure();
                failure.setName(String.join(" ", command));
                failure.setReason("exit code " + exec.exitCode() + "；命令未通过");
                failure.setSeverity("ERROR");
                failures.add(failure);
            }
            commandTexts.add(String.join(" ", command));
            if (safeExec.stdout() != null && !safeExec.stdout().isBlank()) {
                stdout.append("[STDOUT ").append(String.join(" ", command)).append("]\n")
                        .append(safeExec.stdout()).append('\n');
            }
            if (safeExec.stderr() != null && !safeExec.stderr().isBlank()) {
                stderr.append("[STDERR ").append(String.join(" ", command)).append("]\n")
                        .append(safeExec.stderr()).append('\n');
            }
        }
        boolean passed = worstExit == 0;
        ExecutionResult merged = new ExecutionResult(true, worstExit, stdout.toString(), stderr.toString(), null);
        TestResult test;
        if (passed || !failures.isEmpty()) {
            test = analyze(input, allowed.size() == 1 ? allowed.get(0).getCommand() : List.of("planned", "verification"),
                    merged);
            test.setFailures(failures.isEmpty() ? test.getFailures() : failures);
        } else {
            test = new TestResult();
            test.setExitCode(worstExit);
            test.setSummary("验证命令执行失败");
        }
        test.setSuccess(passed);
        test.setVerificationMode("COMMAND");
        test.setCommand(String.join(" && ", commandTexts));
        test.setStdout(merged.stdout());
        test.setStderr(merged.stderr());
        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setTestResult(test);
        // 任一验证命令失败 → TEST_FAILED 交 Review 裁决，Test 不自行判定任务失败。
        outcome.setOutcome(passed ? RunOutcome.SUCCEEDED : RunOutcome.TEST_FAILED);
        outcome.setMessage(test.getSummary());
        return outcome;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 由 LLM 分析真实输出；分析失败或非法时退回基于真实执行的结果，不伪造分析。
     */
    private TestResult analyze(AgentInput input, List<String> command, ExecutionResult exec) {
        String system = promptBuilder.buildSystem(input.getAgentPrompt());
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
            log.warn("test result analysis failed workspaceId={}", input.getWorkspaceId(), e);
            return fallback(exec, command);
        }
    }

    /**
     * 模型分析不可用时保留真实执行事实，但不将模型或异常原文带入公共 TaskRun 日志。
     */
    private TestResult fallback(ExecutionResult exec, List<String> command) {
        TestResult test = new TestResult();
        test.setExitCode(exec.exitCode());
        test.setCommand(String.join(" ", command));
        test.setStdout(exec.stdout());
        test.setStderr(exec.stderr());
        test.setSummary("测试已执行，但模型未能生成可用的分析结果");
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
        failure.setReason("工作区未检测到 pom.xml / build.gradle / package.json 或 tests/*.test.js 等受支持的测试入口，"
                + "无法确定安全测试命令");
        failure.setSeverity("ERROR");
        test.setFailures(List.of(failure));
        // 项目未配置测试是确定性配置问题，不属于基础设施故障；但不判定任务失败——测试未执行
        // 统一 TEST_FAILED 交 Review 独立裁决代码逻辑（代码正确则放行并如实标注「未执行测试」，
        // 有 MAJOR+ 缺陷则打回 Coding）。needsCodingFix 仅作分析信息记录，不决定路由。
        test.setNeedsCodingFix(false);

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setOutcome(RunOutcome.TEST_FAILED);
        outcome.setTestResult(test);
        outcome.setMessage(test.getSummary());
        outcome.setFailureCode("TEST_COMMAND_NOT_FOUND");
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
        test.setAssertionResults(verifyAssertions(input, targets, available));
        test.setSummary(failures.isEmpty()
                ? "未检测到项目测试命令，已完成文件断言：" + String.join(", ", checks)
                : "文件断言未通过：" + failures.get(0).getReason());

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setTestResult(test);
        // 文件断言失败也交 Review 裁决，Test 不自行判定任务失败。
        outcome.setOutcome(failures.isEmpty() ? RunOutcome.SUCCEEDED : RunOutcome.TEST_FAILED);
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
        // 缺 Developer 检查报告也交 Review 裁决，Test 不自行判定任务失败。
        outcome.setOutcome(hasReport ? RunOutcome.SUCCEEDED : RunOutcome.TEST_FAILED);
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

    /**
     * 执行 Plan 输出的可选结构化断言（machineAssertions），产出断言校验事实供 Review 判断，
     * 但不改变 Test 的通过/失败——断言是"预期信号而非裁决"，Coding 因合理原因偏离断言时由
     * Review 结合其偏差声明做最终判断。只校验断言文件属于本次 Coding 实际修改目标（targets）
     * 的条目；计划预期改 A、实际改了 B 的分歧不在 Test 终审，留给 Review。
     */
    private List<TestResult.FileAssertion> verifyAssertions(AgentInput input, List<String> targets,
                                                            Set<String> available) {
        PlanResult plan = input.getPlanResult();
        if (plan == null || plan.getImplementationSteps() == null || plan.getImplementationSteps().isEmpty()) {
            return List.of();
        }
        List<TestResult.FileAssertion> results = new ArrayList<>();
        for (PlanResult.ImplementationStep step : plan.getImplementationSteps()) {
            if (step.getMachineAssertions() == null) {
                continue;
            }
            for (PlanResult.Assertion assertion : step.getMachineAssertions()) {
                String file = normalizePath(assertion.getFile());
                if (!targets.contains(file)) {
                    continue;
                }
                results.add(runAssertion(input.getWorkspaceId(), file, assertion, available));
            }
        }
        return results;
    }

    /**
     * 单条断言的确定性校验：EXISTS 查文件树、EMPTY 复用 {@link #isEmptyFile}、LINES_* 按 \n
     * 分割段数（空内容为 0 行）、CONTAINS/NOT_CONTAINS 做子串判断。结果写入校验事实，不裁决。
     */
    private TestResult.FileAssertion runAssertion(UUID workspaceId, String file, PlanResult.Assertion assertion,
                                                  Set<String> available) {
        TestResult.FileAssertion result = new TestResult.FileAssertion();
        result.setFile(file);
        result.setType(assertion.getType());
        result.setExpected(assertion.getValue());
        switch (assertion.getType()) {
            case "EXISTS" -> {
                boolean exists = available.contains(file);
                result.setActual(exists ? "存在" : "不存在");
                result.setPassed(exists);
            }
            case "EMPTY" -> {
                WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, file);
                String content = read == null || !read.isOk() || read.getContent() == null ? "" : read.getContent();
                boolean empty = isEmptyFile(read, content);
                result.setActual(empty ? "空" : "非空");
                result.setPassed(empty);
            }
            case "LINES_EQ", "LINES_GT", "LINES_LT" -> {
                String content = readFileContent(workspaceId, file);
                int lines = countLines(content);
                int expected = assertion.getValue() == null ? -1 : Integer.parseInt(assertion.getValue());
                boolean passed = switch (assertion.getType()) {
                    case "LINES_EQ" -> lines == expected;
                    case "LINES_GT" -> lines > expected;
                    default -> lines < expected;
                };
                result.setActual(String.valueOf(lines));
                result.setPassed(passed);
            }
            case "CONTAINS", "NOT_CONTAINS" -> {
                String content = readFileContent(workspaceId, file);
                String needle = assertion.getValue() == null ? "" : assertion.getValue();
                boolean contains = content.contains(needle);
                boolean passed = assertion.getType().equals("CONTAINS") ? contains : !contains;
                result.setActual(contains ? "包含" : "不包含");
                result.setPassed(passed);
            }
            case "ENDS_WITH_NEWLINE" -> {
                WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, file);
                String content = read == null || !read.isOk() || read.getContent() == null ? "" : read.getContent();
                // 优先用 Worker 返回的换行元数据（按行重组后 content 可能丢失尾部换行）；缺失时回退内容判断。
                boolean endsWithNewline = read != null && read.isOk() && read.getEndsWithNewline() != null
                        ? read.getEndsWithNewline()
                        : content.endsWith("\n");
                boolean expected = assertion.getValue() == null || !"false".equalsIgnoreCase(assertion.getValue().trim());
                boolean passed = endsWithNewline == expected;
                result.setActual(endsWithNewline ? "以换行结尾" : "不以换行结尾");
                result.setPassed(passed);
            }
            default -> {
                result.setActual("不支持的类型");
                result.setPassed(false);
            }
        }
        return result;
    }

    private String readFileContent(UUID workspaceId, String file) {
        WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, file);
        return read == null || !read.isOk() || read.getContent() == null ? "" : read.getContent();
    }

    /** 内容行数：空内容为 0 行；按 String.lines() 语义统计（末尾换行不产生额外空行），
     *  与编辑器显示行数及 Plan "行数 = N" 的预期一致。 */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return (int) content.lines().count();
    }

    /**
     * 环境阻塞：测试命令已真实执行但非零退出，且被确定性判定为环境/依赖/网络/服务/超时或构建工具
     * 不可用（非本次代码缺陷）。不再同相位盲重试，而是携带环境证据转交 Review 兜底审查代码逻辑：
     * 代码无误则放行（终态如实标注「测试因环境问题未执行」），代码有误则回 Coding 修复。
     */
    private AgentRunOutcome environmentBlocked(AgentInput input, List<String> command,
                                               ExecutionResult exec, String failureCode) {
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setExitCode(exec.exitCode());
        test.setCommand(String.join(" ", command));
        test.setVerificationMode("COMMAND");
        test.setStdout(exec.stdout());
        test.setStderr(exec.stderr());
        test.setEnvironmentFailureCode(failureCode);
        test.setSummary("测试因环境问题未能完成验证：" + failureCode);
        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setTestResult(test);
        outcome.setOutcome(RunOutcome.TEST_FAILED);
        outcome.setMessage("test environment blocked: " + failureCode);
        outcome.setFailureCode(failureCode);
        log.warn("tester environment blocked workspaceId={} failureCode={}",
                input.getWorkspaceId(), failureCode);
        return outcome;
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

    /**
     * 测试命令超时：不再走同相位基础设施重试，也不判定任务失败。
     * <p>
     * 10 分钟测试时限内未完成，往往是测试目标本身跑不完（如 Android SDK 缺失、依赖反复下载）
     * 等确定性原因，重试大概率再次超时。统一 {@link RunOutcome#TEST_FAILED} 交 Review 独立裁决：
     * 代码无 MAJOR+ 缺陷则放行并如实标注「测试执行超时未完成验证」，有 MAJOR+ 缺陷则打回 Coding。
     * 保留真实 exitCode/stdout/stderr 供诊断展示，failureCode 用稳定码 TEST_EXECUTION_TIMEOUT。
     * 不设 environmentFailureCode——超时未必不可由代码修复（如死循环），不能因此阻断打回 Coding。
     */
    private AgentRunOutcome testExecutionTimeout(AgentInput input, List<String> command, ExecutionResult exec) {
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setExitCode(exec.exitCode());
        test.setCommand(String.join(" ", command));
        test.setStdout(exec.stdout());
        test.setStderr(exec.stderr());
        test.setVerificationMode("COMMAND");
        test.setSummary("测试命令 " + String.join(" ", command) + " 执行超时（" + TEST_TIMEOUT.toMinutes()
                + " 分钟时限），未完成验证，已转交 Review 裁决");
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName("test execution timeout");
        failure.setReason("测试在 " + TEST_TIMEOUT.toMinutes() + " 分钟时限内未完成，未给出真实通过/失败结论");
        failure.setSeverity("ERROR");
        test.setFailures(List.of(failure));
        test.setNeedsCodingFix(false);

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setOutcome(RunOutcome.TEST_FAILED);
        outcome.setTestResult(test);
        outcome.setMessage(test.getSummary());
        outcome.setFailureCode("TEST_EXECUTION_TIMEOUT");
        log.warn("tester execution timeout workspaceId={}", input.getWorkspaceId());
        return outcome;
    }

    private String infrastructureCode(String message) {
        String value = message == null ? "" : message.toUpperCase(java.util.Locale.ROOT);
        if (value.contains("SANDBOX_WORKER_UNAVAILABLE")) return "SANDBOX_WORKER_UNAVAILABLE";
        if (value.contains("WORKSPACE_WRITE_LEASE_LOST")) return "WORKSPACE_WRITE_LEASE_LOST";
        if (value.contains("SANDBOX_NOT_FOUND")) return "SANDBOX_NOT_FOUND";
        if (value.contains("DOCKER_EXEC") || value.contains("DOCKER_ENGINE")) return "DOCKER_EXEC_FAILED";
        if (value.contains("BUILD ENVIRONMENT UNAVAILABLE")) return "BUILD_ENVIRONMENT_UNAVAILABLE";
        if (value.contains("TEST_DEPENDENCY_UNAVAILABLE")) return "TEST_DEPENDENCY_UNAVAILABLE";
        if (value.contains("TEST_NETWORK_UNAVAILABLE")) return "TEST_NETWORK_UNAVAILABLE";
        if (value.contains("TEST_SERVICE_UNAVAILABLE")) return "TEST_SERVICE_UNAVAILABLE";
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
