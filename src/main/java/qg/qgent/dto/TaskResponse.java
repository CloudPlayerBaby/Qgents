package qg.qgent.dto;
import lombok.*;
import java.util.*;
/** Task view including its single workspace and repository scope. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {
    private String id;
    private String projectId;
    private String requirementGroupId;
    private String triggerMessageId;
    private String title;
    private String requirement;
    private String status;
    private String workspaceId;
    private List<String> repositoryIds;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
