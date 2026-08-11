package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务运行列表摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunSummaryResponse {
    private String id;
    private String projectId;
    private String taskId;
    private String taskStepId;
    private String agentId;
    private String orchestrationRunId;
    private String workPackageId;
    private String subTaskId;
    private String repositoryId;
    private String groupId;
    private String role;
    private String status;
    private String retryOfTaskRunId;
    private String createdAt;
    private String updatedAt;
}
