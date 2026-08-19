package qg.qgent.orchestration;

import java.util.Locale;

/**
 * TaskStep 的执行语义。步骤角色用于选 Agent，执行模式用于决定工具权限和成功条件。
 */
public enum TaskStepExecutionMode {
    /** 允许修改工作区，且必须产生真实文件变更。 */
    MUTATE(true, true),
    /** 只读核验，目标状态已满足时允许零修改成功。 */
    VERIFY(false, false),
    /** 只读测试。 */
    TEST(false, false),
    /** 只读审查。 */
    REVIEW(false, false),
    /** 规划阶段，不访问写工具。 */
    PLAN(false, false);

    private final boolean allowWrite;
    private final boolean requireChange;

    TaskStepExecutionMode(boolean allowWrite, boolean requireChange) {
        this.allowWrite = allowWrite;
        this.requireChange = requireChange;
    }

    public boolean allowWrite() {
        return allowWrite;
    }

    public boolean requireChange() {
        return requireChange;
    }

    /** 兼容迁移前没有执行模式的旧步骤。 */
    public static TaskStepExecutionMode resolve(String value, String role) {
        if (value != null && !value.isBlank()) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 使用按角色收敛的安全默认值。
            }
        }
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedRole) {
            case "DEVELOPER" -> MUTATE;
            case "TESTER" -> TEST;
            case "REVIEWER" -> REVIEW;
            case "PLANNER" -> PLAN;
            default -> VERIFY;
        };
    }
}
