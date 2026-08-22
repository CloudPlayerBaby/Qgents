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
     * Maven（mvn test / sh ./mvnw test）、Gradle（gradle test / sh ./gradlew test）和
     * npm（npm test）。这些命令与 Worker 的固定 development.run 目录一一对应，
     * 不接受文件路径、模块参数或其他 argv。
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
        return false;
    }

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
        return null;
    }

    /** 按本次 Coding 实际修改目标筛选命令，避免无关构建文件触发错误的 Gradle/Maven 测试。 */
    public ResolvedCommand resolveCommand(List<String> files, List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return resolveCommand(files);
        }
        if (targets.stream().map(TestCommandResolver::fileName).anyMatch(TestCommandResolver::isNodeTarget)) {
            return resolveNpmCommandForNodeTargets(files, targets);
        }
        ResolvedCommand resolved = resolveCommand(files);
        if (resolved == null) return null;
        if (!targetsMatchCommand(resolved.command(), resolved.repositoryPath(), targets)) return null;
        return resolved;
    }

    /**
     * Node 目标只在所属 package.json 仓库执行 npm test，避免多仓库 Workspace 中后端 Maven
     * 入口遮蔽前端测试。无 package.json 时不执行裸 node，交由文件断言路径处理。
     */
    private ResolvedCommand resolveNpmCommandForNodeTargets(List<String> files, List<String> targets) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        return files.stream()
                .filter(file -> "package.json".equals(fileName(file)))
                .map(TestCommandResolver::candidateRoot)
                .distinct()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .filter(root -> targets.stream().allMatch(target -> target != null
                        && belongsToRoot(root, target.replace('\\', '/'))))
                .map(root -> new ResolvedCommand(List.of("npm", "test"), root.isEmpty() ? null : root))
                .findFirst()
                .orElse(null);
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
        return root(normalized);
    }

    private static String relative(String root, String path) {
        return root.isEmpty() ? path : path.substring(root.length() + 1);
    }

    private static boolean belongsToRoot(String root, String path) {
        return root.isEmpty() || path.startsWith(root + "/");
    }
}
