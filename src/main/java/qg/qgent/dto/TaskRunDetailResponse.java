package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;

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
    /** 所属步骤标题；步骤已删除或缺失时为 null。 */
    private String taskStepTitle;
    private String agentId;
    /** 执行 Agent 摘要；未分配时为 null。 */
    private AgentSummary agent;
    private String role;
    private String status;
    /** 脱敏的用户可见状态摘要。 */
    private String statusSummary;
    private String retryOfTaskRunId;
    /**
     * 等待/阻塞/失败原因摘要；无等待或失败时 null。
     */
    private TaskStatusReason statusReason;
    /**
     * 产物摘要，由受控执行服务写入；未产出时为空。
     */
    private Map<String, Object> artifactSummary;
    /** 执行器已持久化的脱敏内部节点轨迹；尚未持久化时返回空数组。 */
    private List<TaskRunStepResponse> steps;
    private String startedAt;
    private String finishedAt;
    /**
     * 执行耗时毫秒数，由 finishedAt-startedAt 派生；任一端时间为空时为 null。
     */
    private Long durationMs;
    private String createdAt;
    private String updatedAt;
}
