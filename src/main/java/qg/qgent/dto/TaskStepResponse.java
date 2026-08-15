package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Persisted workflow-step view.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStepResponse {
    private String id;
    private String taskId;
    private Integer sequenceNo;
    private String title;
    private String instruction;
    private String role;
    private String assignedAgentId;
    private String acceptanceCriteria;
    private String status;
    private List<TaskRepositoryScopeRequest> repositoryScopes;
}
