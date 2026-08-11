package qg.qgent.dto;
import lombok.*;
import java.util.*;
/** Persisted workflow-step view. */
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
