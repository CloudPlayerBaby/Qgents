package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Request to create an MR from a Task's persisted Workspace repository head. */
@Data
public class MergeRequestCreateRequest {
    /** Task whose Workspace branch is proposed for merge. */
    @NotNull
    private UUID taskId;
    @NotNull
    private UUID repositoryId;
    /** 目标分支名。 */
    @NotBlank
    private String targetBranch;
    /** MR 标题。 */
    @NotBlank
    private String title;
}
