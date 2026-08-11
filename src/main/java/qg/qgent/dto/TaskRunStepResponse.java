package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务运行内工作流节点状态。
 * 节点状态枚举：PENDING/RUNNING/PASSED/FAILED/SKIPPED/CANCELLED。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunStepResponse {
    private String node;
    private String status;
    private String startedAt;
    private String finishedAt;
    private Long durationMs;
    private String errorCode;
}
