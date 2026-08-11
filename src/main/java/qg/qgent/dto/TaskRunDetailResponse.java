package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 单次任务运行详情：状态、关联子任务、步骤与产物摘要。
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
    private String orchestrationRunId;
    private String workPackageId;
    private String subTaskId;
    private String repositoryId;
    private String groupId;
    private String role;
    private String status;
    private String retryOfTaskRunId;
    private List<TaskRunStepResponse> steps;
    /** 产物摘要，由受控执行服务写入；未产出时为空。 */
    private Map<String, Object> artifactSummary;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
    private String updatedAt;
}
