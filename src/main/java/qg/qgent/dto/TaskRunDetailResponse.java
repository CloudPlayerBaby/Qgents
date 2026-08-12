package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Details of one TaskStep execution attempt and its Task-level result summary. */
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
    private String createdAt;
    private String updatedAt;
}
