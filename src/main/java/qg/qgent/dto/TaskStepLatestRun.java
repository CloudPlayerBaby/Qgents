package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TaskStep 最新一次执行尝试的轻量摘要。
 * <p>
 * 供流程卡片展示该步骤最近一次 TaskRun 的状态与时间；完整运行信息通过 TaskRun 详情接口获取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStepLatestRun {

    /**
     * 任务运行 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "任务运行 ID")
    private String id;

    /**
     * 运行状态。
     */
    @Schema(description = "运行状态")
    private String status;

    /**
     * 开始时间（UTC），可为 null。
     */
    @Schema(description = "开始时间")
    private String startedAt;

    /**
     * 结束时间（UTC），可为 null。
     */
    @Schema(description = "结束时间")
    private String finishedAt;

    /**
     * 执行耗时毫秒数，可为 null。
     */
    @Schema(description = "执行耗时毫秒")
    private Long durationMs;
}
