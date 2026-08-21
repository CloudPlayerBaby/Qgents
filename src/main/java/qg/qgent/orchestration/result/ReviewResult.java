package qg.qgent.orchestration.result;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Review Agent 的结构化产出：是否通过、审查摘要、发现项、整体建议与是否需要 Coding 修复。
 * <p>
 * success 的最终判定由 ReviewAgent 依据严重度策略给出：存在 BLOCKER/MAJOR 时强制 FAIL，
 * 仅 MINOR/INFO（含空 findings）时必 PASS；LLM 返回的 success 布尔值不参与判定，严重度
 * 归一化由 ReviewVerdictComputer 完成。findings 与 suggestions 供重试的
 * Coding Agent 判断下一步应修复什么。
 */
@Data
public class ReviewResult {
    /**
     * 是否通过审查，由严重度策略判定。
     */
    private boolean success;
    /**
     * 审查摘要。
     */
    private String summary;
    /**
     * 审查发现列表。
     */
    private List<Finding> findings = new ArrayList<>();
    /**
     * 整体改进建议。
     */
    private List<String> suggestions = new ArrayList<>();
    /**
     * 是否需要 Coding Agent 修复；false 且未通过时不可自动修复。
     */
    private boolean needsCodingFix;
    /**
     * 稳定失败分类码（如 REVIEW_ASSERTION_TARGET_NOT_FOUND），仅当审查因可分类的
     * 确定性原因失败时由模型输出；供任务级失败语义区分「验收目标缺失」等具体原因，
     * 而不是回退到笼统的「审查未通过」。仅在未通过时非空。
     */
    private String failureCode;
    /**
     * 是否在「测试未真实跑完」的情况下通过审查。true 表示 Review 放行时测试并未给出真实通过/失败
     * 结论（环境阻塞、执行超时、未检测到测试命令等，见 {@link qg.qgent.orchestration.result.TestResult#isInconclusive()}），
     * 终态需如实标注「代码审查通过，但测试未完成验证」，不得描述为测试通过。
     * 测试真实执行并给出失败结论、但 Review 判定无 BLOCKER/MAJOR 而放行时，本标记保持 false，
     * 由终态卡片单独如实标注「测试未通过」。
     */
    private boolean testsNotExecuted;
    /**
     * 结构化修复动作：审查发现可确定性执行的修复（如缺失末尾换行）时由模型输出，编排器据此
     * 选择受控修复动作（如 ENSURE_TRAILING_NEWLINE），避免 AI 手工重拼文件或反复生成错误 patch。
     * 可选；未输出时回退到「打回 Coding 自行修复」。
     */
    private RepairAction repairAction;

    /**
     * 结构化修复动作：类型 + 目标文件 + 说明。type 目前支持 ENSURE_TRAILING_NEWLINE；
     * file 为 Workspace 相对路径。
     */
    @Data
    public static class RepairAction {
        /**
         * 修复动作类型：ENSURE_TRAILING_NEWLINE（确保文件以换行结尾）等。
         */
        private String type;
        /**
         * 目标文件（Workspace 相对路径）。
         */
        private String file;
        /**
         * 修复原因/说明（受控，不携带原始日志）。
         */
        private String reason;
    }

    /**
     * 单个审查发现。
     */
    @Data
    public static class Finding {
        /**
         * 严重程度：BLOCKER/MAJOR/MINOR/INFO。
         */
        private String severity;
        /**
         * 定位文件（相对路径）。
         */
        private String file;
        /**
         * 定位行号，无法确定时为 null。
         */
        private Integer line;
        /**
         * 问题描述。
         */
        private String issue;
        /**
         * 建议修改。
         */
        private String suggestion;
    }
}
