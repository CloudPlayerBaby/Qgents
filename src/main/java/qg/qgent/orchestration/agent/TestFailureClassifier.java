package qg.qgent.orchestration.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 确定性测试失败分类器：区分"真实代码缺陷"与"环境/超时/依赖网络失败"。
 * <p>
 * Test 真实 exit code != 0 时，TestAgent 在 LLM 分析之前调用本类判断失败归属。只有能证明失败与
 * 本次改动无关（超时、依赖解析失败、网络不可达、服务连接被拒、构建工具链/SDK 缺失，且输出未引用
 * 任何已修改文件、未修改构建/清单文件）才归为 {@link Classification#ENVIRONMENT}，走 FAILED_INFRASTRUCTURE
 * 同相位重试、不占用质量修复循环；其余一律归 {@link Classification#CODE_DEFECT}，维持打回 Coding 的既有质量循环。
 * <p>
 * 纯逻辑、无 LLM、无 Spring，可独立单元测试。环境关键字只做保守方向使用：命中守卫（输出引用已修改
 * 文件，或本次改动了构建文件）时强制按代码缺陷处理，避免"改坏 pom 坐标 / 改错连接配置"这类真实
 * 缺陷因命中环境关键字而被放走。
 */
public class TestFailureClassifier {

    /** 分类结果类型。 */
    public enum Classification {
        /** 失败可归因于本次改动引入的缺陷，打回 Coding 修复。 */
        CODE_DEFECT,
        /** 失败为环境/超时/依赖网络问题，走同相位基础设施重试，不占用质量修复循环。 */
        ENVIRONMENT
    }

    /** 分类判定结果：类型 + 稳定基础设施失败码（CODE_DEFECT 时 failureCode 为 null）。 */
    public record Verdict(Classification classification, String failureCode) {

        static Verdict environment(String code) {
            return new Verdict(Classification.ENVIRONMENT, code);
        }

        static Verdict codeDefect() {
            return new Verdict(Classification.CODE_DEFECT, null);
        }
    }

    /**
     * 构建/清单文件：本次改动了它们时，依赖/网络/服务失败更可能是改动引入的
     * （如改坏了 pom 坐标、依赖锁、registry 地址）。
     */
    private static final Set<String> BUILD_MANIFEST_NAMES = Set.of(
            "pom.xml", "mvnw", "mvnw.cmd",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradlew", "gradlew.bat", "gradlew.cmd",
            "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    /** 依赖解析失败（Maven/Gradle 找不到构件；含 -pl 缺兄弟模块产物、项目不在 reactor 内的场景）。 */
    private static final String[] DEPENDENCY_PATTERNS = {
            "could not resolve dependencies",
            "could not find artifact",
            "failed to collect dependencies",
            "could not find the selected project"
    };

    /** 网络不可达（下载依赖、registry、DNS）。 */
    private static final String[] NETWORK_PATTERNS = {
            "unknownhost",
            "eai_again",
            "enotfound",
            "connect timed out",
            "network is unreachable",
            "could not transfer"
    };

    /** 服务连接失败（数据库/缓存/消息队列）。 */
    private static final String[] SERVICE_PATTERNS = {
            "connection refused",
            "communications link failure",
            "could not connect"
    };

    /**
     * 构建工具链缺失：JDK/JRE、Maven 自带编译器、Gradle JVM、Android SDK、Node 运行时等。
     * 此类失败有明确的环境语义，不是本次代码可修复的缺陷（修代码不能凭空变出 SDK）；
     * 与命令不可用（{@code isCommandUnavailable}）共用失败码 BUILD_ENVIRONMENT_UNAVAILABLE。
     */
    private static final String[] TOOLCHAIN_PATTERNS = {
            "no compiler is provided in this environment",
            "unable to locate a java runtime",
            "unable to locate java",
            "unable to find any jvms",
            "sdk location not found",
            "failed to find target android",
            "requires android sdk",
            "could not create the java virtual machine",
            "java runtime not found",
            "not recognized as an internal or external command"
    };

    /** 超时：进程被超时杀掉（exit 124/143）或输出声明超时。 */
    private static final String[] TIMEOUT_PATTERNS = {
            "timed out", "timeout", "超时"
    };

    /**
     * 依据真实执行结果与本次改动目标判定失败归属。
     *
     * @param exitCode 真实退出码（来自已脱敏执行结果）。
     * @param stdout   脱敏后的标准输出（全量，未截断，避免失败明细被裁剪）。
     * @param stderr   脱敏后的标准错误。
     * @param targets  本次 Coding 实际修改的文件相对路径（服务端可信来源）。
     * @return 判定结果；CODE_DEFECT 时 failureCode 为 null。
     */
    public Verdict classify(int exitCode, String stdout, String stderr, List<String> targets) {
        // 通过的执行（exit 0）不可能是环境阻塞，即使输出恰好提到环境类字样（如某条日志）。
        if (exitCode == 0) {
            return Verdict.codeDefect();
        }
        String output = (safe(stdout) + "\n" + safe(stderr)).toLowerCase(Locale.ROOT);
        if (isTimeout(exitCode, output)) {
            return Verdict.environment("TEST_EXECUTION_TIMEOUT");
        }
        // 守卫：失败与本次改动相关 → 一律视为代码缺陷，避免环境关键字误放走真 bug。
        if (referencesModifiedFile(output, targets) || modifiesBuildManifest(targets)) {
            return Verdict.codeDefect();
        }
        if (matchesAny(output, DEPENDENCY_PATTERNS)) {
            return Verdict.environment("TEST_DEPENDENCY_UNAVAILABLE");
        }
        if (matchesAny(output, NETWORK_PATTERNS)) {
            return Verdict.environment("TEST_NETWORK_UNAVAILABLE");
        }
        if (matchesAny(output, SERVICE_PATTERNS)) {
            return Verdict.environment("TEST_SERVICE_UNAVAILABLE");
        }
        // 构建工具链缺失（JDK/编译器/SDK/Node 等）：环境缺陷而非代码缺陷，修代码无法凭空补环境。
        // 守卫已在上方拦截「改坏构建文件或输出引用已修改文件」的场景，此处只放行明确的环境措辞。
        if (matchesAny(output, TOOLCHAIN_PATTERNS)) {
            return Verdict.environment("BUILD_ENVIRONMENT_UNAVAILABLE");
        }
        return Verdict.codeDefect();
    }

    private boolean isTimeout(int exitCode, String output) {
        if (exitCode == 124 || exitCode == 143) {
            return true;
        }
        return matchesAny(output, TIMEOUT_PATTERNS);
    }

    /** 输出是否引用任一已修改文件的 basename（小写子串匹配，仅做保守方向使用）。 */
    private boolean referencesModifiedFile(String output, List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        for (String target : targets) {
            String basename = fileName(target);
            if (basename.isBlank()) {
                continue;
            }
            if (output.contains(basename.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 本次改动是否包含构建/清单文件：改动了它们时，依赖/网络/服务失败优先视为改动引入。 */
    private boolean modifiesBuildManifest(List<String> targets) {
        if (targets == null) {
            return false;
        }
        for (String target : targets) {
            String basename = fileName(target);
            if (BUILD_MANIFEST_NAMES.contains(basename)) {
                return true;
            }
        }
        return false;
    }

    /** 取路径 basename（兼容反斜杠），空路径返回空串。 */
    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private boolean matchesAny(String output, String[] patterns) {
        for (String pattern : patterns) {
            if (output.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
