package qg.qgent.orchestration.tool;

/**
 * Coding Agent 可调用的固定开发命令标识。
 * <p>
 * 该枚举是主后端与 Worker 之间的稳定契约；不传递 argv、环境变量或工作目录。
 * Git 状态和 Diff 必须使用既有受控 Git API，不通过 Sandbox 执行。
 */
public enum DevelopmentCommandId {
    MAVEN_TEST,
    MAVEN_PACKAGE,
    MAVEN_WRAPPER_TEST,
    GRADLE_TEST,
    GRADLE_WRAPPER_TEST,
    NPM_TEST
}
