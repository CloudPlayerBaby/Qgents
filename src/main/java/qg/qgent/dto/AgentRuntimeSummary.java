package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 运行时摘要（契约 v1.8.0 §20，成员 B B06）。
 * <p>
 * concurrencyLimit 当前无并发限制配置，恒为 null；
 * skillAccessScope / memoryAccessScope 当前均为项目维度隔离，返回 "PROJECT"。
 * 不返回 Prompt、工具凭据或 Memory 完整内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeSummary {

    /**
     * 运行状态：IDLE / RUNNING（activeRunCount &gt; 0 时为 RUNNING）。
     */
    @Schema(description = "运行状态：IDLE / RUNNING")
    private String status;

    /**
     * 当前活动运行数。
     */
    @Schema(description = "当前活动运行数")
    private long activeRunCount;

    /**
     * 并发上限；当前无配置，恒为 null。
     */
    @Schema(description = "并发上限，当前恒为 null")
    private Long concurrencyLimit;

    /**
     * 分配用量。
     */
    @Schema(description = "分配用量")
    private AssignmentUsage assignmentUsage;

    /**
     * Skill 访问范围；当前恒为 PROJECT。
     */
    @Schema(description = "Skill 访问范围，当前恒为 PROJECT")
    private String skillAccessScope;

    /**
     * Memory 访问范围；当前恒为 PROJECT。
     */
    @Schema(description = "Memory 访问范围，当前恒为 PROJECT")
    private String memoryAccessScope;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignmentUsage {
        @Schema(description = "需求群分配用量")
        private AssignmentCount requirementGroups;
        @Schema(description = "工作流分配用量（当前无数据源，均为 0）")
        private AssignmentCount workflows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignmentCount {
        @Schema(description = "已分配数量")
        private long assignedCount;
        @Schema(description = "可分配数量")
        private long assignableCount;
    }
}
