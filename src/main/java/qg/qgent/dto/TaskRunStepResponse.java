package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TaskRun 内部节点轨迹。仅允许由执行服务持久化的真实节点状态填充，未提供时返回空数组。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunStepResponse {
    @Schema(description = "执行节点名称")
    private String node;
    @Schema(description = "节点状态")
    private String status;
    private String startedAt;
    private String finishedAt;
    private Long durationMs;
    private String errorCode;
}
