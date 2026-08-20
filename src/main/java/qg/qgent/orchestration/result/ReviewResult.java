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
