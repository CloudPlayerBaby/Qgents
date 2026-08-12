package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 任务运行详情：完整返回一次 TaskStep 执行尝试的状态、时序与产物摘要。
 * 相比 {@link TaskRunSummaryResponse}，详情额外包含 startedAt/finishedAt/durationMs
 * 与 artifactSummary，供执行详情页与结果审查使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunDetailResponse {
    private String id;
    private String projectId;
    private String taskId;
    private String taskStepId;
    private String agentId;
    private String role;
    private String status;
    private String retryOfTaskRunId;
    /** 产物摘要，由受控执行服务写入；未产出时为空。 */
    private Map<String, Object> artifactSummary;
    private String startedAt;
    private String finishedAt;
    /** 执行耗时毫秒数，由 finishedAt-startedAt 派生；任一端时间为空时为 null。 */
    private Long durationMs;
    private String createdAt;
    private String updatedAt;
}
