package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TaskRun 内部节点轨迹。仅由执行器持久化的脱敏观测投影而来；未持久化时返回空数组。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunStepResponse {
    @Schema(description = "执行节点名称")
    private String node;
    @Schema(description = "节点状态", allowableValues = {"PENDING", "RUNNING", "PASSED", "FAILED", "SKIPPED", "CANCELLED"})
    private String status;
    private String startedAt;
    private String finishedAt;
    private Long durationMs;
    private String errorCode;
}
