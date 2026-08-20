package qg.qgent.orchestration.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 安全测试命令解析器：依据工作区文件树检测构建工具，返回白名单命令模板。
 * <p>
 * LLM 不参与命令选择，本类只允许返回硬编码的、与测试相关的构建命令，
 * 从结构上排除 rm / sudo / curl / wget / ssh / git push 等与测试无关的危险命令。
 * 检测不到受支持构建工具时返回 null，调用方不得执行任何命令。
 * 当前策略：Maven（pom.xml）、Gradle（build.gradle* / settings.gradle）、Node（package.json）。
 */
public class TestCommandResolver {

    /** 解析出的固定测试向量及其工作区仓库目录。 */
    public record ResolvedCommand(List<String> command, String repositoryPath) {
    }

    /**
     * 校验 Planner 输出的结构化验证命令是否命中白名单模板。
     * <p>
     * Plan 来自 LLM，不能信任其任意 shell 指令；只有与自动检测同源的固定测试模板可放行：
     * Maven（mvn test / sh ./mvnw test）、Gradle（gradle test / sh ./gradlew test）、
     * npm（npm test）与 Node 测试文件（node &lt;tests/*.test.js&gt; 等）。带模块参数的
     * -pl / :module:test 收敛由运行时 {@link #scopeToModule} 完成，Plan 不输出。
     *
     * @param command 待校验的命令向量
     * @return 命中白名单模板返回 true；null / 空 / 任意 shell 命令返回 false
     */
    public static boolean isAllowedVerificationCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        if (command.equals(List.of("mvn", "test")) || command.equals(List.of("sh", "./mvnw", "test"))) {
            return true;
        }
        if (command.equals(List.of("gradle", "test")) || command.equals(List.of("sh", "./gradlew", "test"))) {
            return true;
        }
        if (command.equals(List.of("npm", "test"))) {
            return true;
        }
        if (command.size() == 2 && "node".equals(command.get(0)) && isNodeTestFile(command.get(1))) {
            return true;
        }
        return false;
    }

    /** 多模块 Maven 中单个子模块的构建入口标记（相对各模块目录自身）。 */
    private static final List<String> MAVEN_MODULE_MARKERS = List.of("pom.xml");
    /** Gradle 多项目中单个子项目的构建入口标记。 */
    private static final List<String> GRADLE_MODULE_MARKERS = List.of("build.gradle", "build.gradle.kts");

    /**
     * 依据文件列表解析安全的测试命令。
     *
     * @param files 工作区文件相对路径列表。
     * @return 白名单命令模板；无受支持构建工具时返回 null。
     */
    public List<String> resolve(List<String> files) {
        ResolvedCommand resolved = resolveCommand(files);
        if (resolved == null) return null;
        return resolved.command();
    }

    /**
     * 解析命令并绑定到具体仓库。多仓库 Workspace 不允许把仓库 Wrapper
     * 当作 Workspace 根目录命令执行。
     */
    public ResolvedCommand resolveCommand(List<String> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        Set<String> roots = files.stream()
                .map(TestCommandResolver::candidateRoot)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        Map<String, Set<String>> filesByRoot = new java.util.LinkedHashMap<>();
        for (String root : roots) {
            filesByRoot.put(root, files.stream().filter(file -> belongsToRoot(root, file))
                    .map(file -> relative(root, file)).collect(java.util.stream.Collectors.toSet()));
        }
        for (String root : roots) {
            Set<String> names = filesByRoot.get(root);
            if (hasFile(names, "pom.xml")) {
                List<String> command = hasFile(names, "mvnw") ? List.of("sh", "./mvnw", "test")
                        : List.of("mvn", "test");
                return new ResolvedCommand(command, root.isEmpty() ? null : root);
            }
        }
        for (String root : roots) {
            Set<String> names = filesByRoot.get(root);
            if (hasFile(names, "build.gradle") || hasFile(names, "build.gradle.kts")
                    || hasFile(names, "settings.gradle") || hasFile(names, "settings.gradle.kts")) {
                List<String> command = hasFile(names, "gradlew") ? List.of("sh", "./gradlew", "test")
                        : List.of("gradle", "test");
                return new ResolvedCommand(command, root.isEmpty() ? null : root);
            }
        }
        for (String root : roots) {
            Set<String> names = filesByRoot.get(root);
            if (hasFile(names, "package.json")) {
                return new ResolvedCommand(List.of("npm", "test"), root.isEmpty() ? null : root);
            }
        }
        // 无 package.json 但有 Node 测试文件（tests/*.test.js / *.spec.js）：直接以 node 执行，
        // 满足「Planner 明确要求 node tests/todo.test.js」的纯 Node 项目；命令来自文件树白名单，
        // 不是 Planner 任意 shell 指令。
        ResolvedCommand node = resolveNodeTestFiles(files, roots, filesByRoot);
        if (node != null) {
            return node;
        }
        return null;
    }

    /**
     * 无 package.json 时，从文件树中寻找 Node 测试文件并生成 {@code node <file>} 命令。
     * 仅接受 {@code tests/} 或 {@code test/} 目录下、以 {@code .test.js} / {@code .spec.js} /
     * {@code .test.mjs} / {@code .spec.mjs} 结尾的文件；路径来自文件树（白名单），
     * 禁止从自然语言或 Planner 文本拼命令。
     */
    private ResolvedCommand resolveNodeTestFiles(List<String> files, Set<String> roots,
                                                 Map<String, Set<String>> filesByRoot) {
        for (String root : roots) {
            Set<String> names = filesByRoot.get(root);
            String testFile = names.stream()
                    .filter(TestCommandResolver::isNodeTestFile)
                    .sorted()
                    .findFirst()
                    .orElse(null);
            if (testFile != null) {
                return new ResolvedCommand(List.of("node", testFile), root.isEmpty() ? null : root);
            }
        }
        return null;
    }

    /** 判断仓库相对路径是否为 Node 测试文件（tests/ 或 test/ 目录下，.test/.spec 后缀）。 */
    private static boolean isNodeTestFile(String relative) {
        if (relative == null || relative.isBlank()) {
            return false;
        }
        String normalized = relative.replace('\\', '/');
        if (!normalized.startsWith("tests/") && !normalized.startsWith("test/")) {
            return false;
        }
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".test.js") || lower.endsWith(".spec.js")
                || lower.endsWith(".test.mjs") || lower.endsWith(".spec.mjs")
                || lower.endsWith(".test.jsx") || lower.endsWith(".spec.jsx");
    }

    /** 按本次 Coding 实际修改目标筛选命令，避免无关构建文件触发错误的 Gradle/Maven 测试。 */
    public ResolvedCommand resolveCommand(List<String> files, List<String> targets) {
        ResolvedCommand resolved = resolveCommand(files);
        if (targets == null || targets.isEmpty()) return resolved;
        if (targets.stream().map(TestCommandResolver::fileName).anyMatch(TestCommandResolver::isNodeTarget)) {
            ResolvedCommand node = resolveNodeCommand(files, targets);
            return node;
        }
        if (resolved == null) return null;
        if (!targetsMatchCommand(resolved.command(), resolved.repositoryPath(), targets)) return null;
        return scopeToModule(files, resolved, targets);
    }

    private ResolvedCommand resolveNodeCommand(List<String> files, List<String> targets) {
        for (String target : targets) {
            String root = root(target.replace('\\', '/'));
            Set<String> names = files.stream().filter(file -> belongsToRoot(root, file))
                    .map(file -> relative(root, file)).collect(java.util.stream.Collectors.toSet());
            if (hasFile(names, "package.json")) {
                return new ResolvedCommand(List.of("npm", "test"), root.isEmpty() ? null : root);
            }
            // 无 package.json 但有 Node 测试文件：直接 node 执行（文件树白名单）。
            String testFile = names.stream().filter(TestCommandResolver::isNodeTestFile).sorted().findFirst()
                    .orElse(null);
            if (testFile != null) {
                return new ResolvedCommand(List.of("node", testFile), root.isEmpty() ? null : root);
            }
        }
        return null;
    }

    private boolean targetsMatchCommand(List<String> command, String repositoryPath, List<String> targets) {
        if (repositoryPath != null && targets.stream().anyMatch(target -> !belongsToRoot(repositoryPath,
                target.replace('\\', '/')))) return false;
        String tool = command.isEmpty() ? "" : command.get(0);
        boolean jvm = targets.stream().map(TestCommandResolver::fileName).anyMatch(TestCommandResolver::isJvmTarget);
        boolean node = targets.stream().map(TestCommandResolver::fileName).anyMatch(TestCommandResolver::isNodeTarget);
        boolean gradle = targets.stream().map(TestCommandResolver::fileName).anyMatch(TestCommandResolver::isGradleTarget);
        boolean maven = targets.stream().map(TestCommandResolver::fileName).anyMatch(TestCommandResolver::isMavenTarget);
        if (tool.equals("gradle") || command.equals(List.of("sh", "./gradlew", "test"))) return jvm || gradle;
        if (tool.equals("mvn") || command.equals(List.of("sh", "./mvnw", "test"))) return jvm || maven;
        if (tool.equals("npm")) return node;
        return true;
    }

    /**
     * 把整仓库测试命令收敛到改动所在的唯一模块/项目：
     * Maven 多模块使用 {@code mvn test -pl <模块>}，Gradle 多项目使用 {@code :<项目路径>:test}。
     * 无法唯一确定模块（无嵌套模块、改动横跨多个模块、或命令不是 Maven/Gradle）时原样返回整仓库命令，
     * 保守不误杀；"缺兄弟模块产物"等依赖解析失败由 TestFailureClassifier 归为环境问题重试。
     */
    private ResolvedCommand scopeToModule(List<String> files, ResolvedCommand resolved, List<String> targets) {
        List<String> command = resolved.command();
        boolean maven = isMavenCommand(command);
        boolean gradle = isGradleCommand(command);
        if (!maven && !gradle) {
            return resolved; // npm 已按 package 根执行，无需收敛
        }
        Set<String> modules = new java.util.TreeSet<>();
        for (String target : targets) {
            String module = moduleForTarget(resolved.repositoryPath(), target, files,
                    maven ? MAVEN_MODULE_MARKERS : GRADLE_MODULE_MARKERS);
            if (module != null) {
                modules.add(module);
            }
        }
        if (modules.size() != 1) {
            return resolved; // 0=单模块仓库，>1=改动横跨多模块，均退回整仓库命令
        }
        String module = modules.iterator().next();
        return maven
                ? new ResolvedCommand(withMavenModule(command, module), resolved.repositoryPath())
                : new ResolvedCommand(withGradleModuleTask(command, module), resolved.repositoryPath());
    }

    private static boolean isMavenCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        return "mvn".equals(command.get(0))
                || (command.size() > 1 && "./mvnw".equals(command.get(1)));
    }

    private static boolean isGradleCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        return "gradle".equals(command.get(0))
                || (command.size() > 1 && "./gradlew".equals(command.get(1)));
    }

    /** Maven 命令在 test 目标前插入 {@code -pl <模块>}，保留 wrapper 选择。 */
    private static List<String> withMavenModule(List<String> command, String module) {
        List<String> scoped = new java.util.ArrayList<>(command);
        int testIndex = scoped.lastIndexOf("test");
        if (testIndex >= 0) {
            scoped.addAll(testIndex, List.of("-pl", module));
        } else {
            scoped.add("-pl");
            scoped.add(module);
        }
        return scoped;
    }

    /** Gradle 命令把 test 任务替换为 {@code :<模块路径>:test}（目录路径转冒号分隔）。 */
    private static List<String> withGradleModuleTask(List<String> command, String module) {
        List<String> scoped = new java.util.ArrayList<>(command);
        String task = ":" + module.replace('/', ':') + ":test";
        int testIndex = scoped.lastIndexOf("test");
        if (testIndex >= 0) {
            scoped.set(testIndex, task);
        } else {
            scoped.add(task);
        }
        return scoped;
    }

    /**
     * 求 target 在 repositoryPath 仓库内所属模块目录（相对仓库根）：包含构建入口文件的最近祖先目录。
     * 无嵌套模块（最近祖先即仓库根）、target 不属于该仓库或为 null 时返回 null，调用方退回整仓库命令。
     */
    private static String moduleForTarget(String repositoryPath, String target, List<String> files,
                                          List<String> buildMarkers) {
        if (target == null) {
            return null;
        }
        String normalized = target.replace('\\', '/');
        String root = repositoryPath == null ? "" : repositoryPath;
        String rel;
        if (root.isEmpty()) {
            rel = normalized;
        } else if (normalized.equals(root)) {
            return null;
        } else if (normalized.startsWith(root + "/")) {
            rel = normalized.substring(root.length() + 1);
        } else {
            return null;
        }
        int idx = rel.lastIndexOf('/');
        while (idx > 0) {
            String candidate = rel.substring(0, idx);
            String entryBase = root.isEmpty() ? candidate : root + "/" + candidate;
            if (hasAnyBuildFile(files, entryBase, buildMarkers)) {
                return candidate;
            }
            idx = rel.lastIndexOf('/', idx - 1);
        }
        return null;
    }

    private static boolean hasAnyBuildFile(List<String> files, String dir, List<String> buildMarkers) {
        for (String marker : buildMarkers) {
            String entry = dir.isEmpty() ? marker : dir + "/" + marker;
            if (files.contains(entry)) {
                return true;
            }
        }
        return false;
    }

    private static String fileName(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static boolean isJvmTarget(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")
                || lower.endsWith(".groovy") || lower.endsWith(".scala");
    }

    private static boolean isNodeTarget(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts")
                || lower.endsWith(".tsx") || lower.equals("package.json")
                || lower.equals("package-lock.json") || lower.equals("pnpm-lock.yaml") || lower.equals("yarn.lock");
    }

    private static boolean isGradleTarget(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("build.gradle") || lower.equals("build.gradle.kts")
                || lower.equals("settings.gradle") || lower.equals("settings.gradle.kts")
                || lower.equals("gradlew") || lower.equals("gradlew.bat");
    }

    private static boolean isMavenTarget(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("pom.xml") || lower.equals("mvnw") || lower.equals("mvnw.cmd");
    }

    /** 判断仓库内相对文件列表是否包含指定入口文件。 */
    private boolean hasFile(Set<String> files, String name) {
        return files.contains(name);
    }

    private static String root(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** 从构建入口文件本身推导完整仓库目录，支持 services/backend 这样的嵌套路径。 */
    private static String candidateRoot(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        String[] markers = {"pom.xml", "mvnw", "mvnw.cmd", "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts", "gradlew", "gradlew.cmd", "package.json"};
        for (String marker : markers) {
            if (normalized.equals(marker)) {
                return "";
            }
            String suffix = "/" + marker;
            if (normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        // Node 测试文件（tests/*.test.js 等）：仓库根 = 去掉 tests/test 段后的目录。
        // tests/todo.test.js → ""（工作区根）；svc-a/tests/a.test.js → "svc-a"。
        if (isNodeTestFile(normalized)) {
            int idx = normalized.indexOf("/tests/");
            if (idx < 0) {
                idx = normalized.indexOf("/test/");
            }
            return idx < 0 ? "" : normalized.substring(0, idx);
        }
        return root(normalized);
    }

    private static String relative(String root, String path) {
        return root.isEmpty() ? path : path.substring(root.length() + 1);
    }

    private static boolean belongsToRoot(String root, String path) {
        return root.isEmpty() || path.startsWith(root + "/");
    }
}
