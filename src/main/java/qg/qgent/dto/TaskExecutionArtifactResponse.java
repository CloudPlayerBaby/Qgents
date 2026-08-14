package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Immutable timeline artifact produced while a Task is planned or executed. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionArtifactResponse {
    private String id;
    private String taskId;
    private String taskRunId;
    private String taskStepId;
    private Integer sequenceNo;
    private String artifactType;
    private Map<String, Object> summary;
    private String createdAt;
}
