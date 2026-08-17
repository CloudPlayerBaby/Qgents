package qg.qgent.orchestration;

/**
 * 代码交付路径常量与校验工具。
 * <p>
 * 两种交付路径：DIFF_FIRST（小功能，先回 Diff 供用户确认再交付）与
 * MR_FIRST（大功能，跳过 Diff 审核直接创建 PR 走代码审查门禁）。
 * 取值必须与 {@code tasks.delivery_mode} 列及对外契约保持一致，统一字符串字面量，
 * 避免散落魔法值。判定优先级：用户显式指定 &gt; Planner 判定 &gt; 硬规则兜底。
 */
public final class DeliveryMode {

    /**
     * 小功能路径：REVIEWER 成功后生成待确认 Diff 批次，用户确认后才进入交付。
     */
    public static final String DIFF_FIRST = "DIFF_FIRST";
    /**
     * 大功能路径：REVIEWER 成功后直接进入交付，创建 PR 并通过质量门禁后合入。
     */
    public static final String MR_FIRST = "MR_FIRST";

    private DeliveryMode() {
    }

    /**
     * 是否为受支持的交付模式取值。
     */
    public static boolean isValid(String mode) {
        return DIFF_FIRST.equals(mode) || MR_FIRST.equals(mode);
    }
}