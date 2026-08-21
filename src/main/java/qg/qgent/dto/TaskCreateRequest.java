package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request to create one task from an active requirement-group conversation.
 */
@Data
public class TaskCreateRequest {
    @NotNull
    @Schema(description = "Active REQUIREMENT group identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID requirementGroupId;

    @Schema(description = "Optional triggering message identifier")
    private UUID triggerMessageId;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Task title", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank
    @Size(max = 10000)
    @Schema(description = "Requirement snapshot", maxLength = 10000, requiredMode = Schema.RequiredMode.REQUIRED)
    private String requirement;

    @Size(max = 20)
    @Schema(description = "Repositories for a new workspace; omit when reusing a workspace")
    private List<@NotNull UUID> repositoryIds;

    @Schema(description = "Existing workspace to reuse for a continuation task")
    private UUID workspaceId;

    @Schema(description = "Previous task continued by this task; required with workspaceId")
    private UUID continuationOfTaskId;

    @Size(max = 512)
    @Schema(description = "可选的公共基线分支名；不接受 commit SHA，缺省用项目仓库默认分支")
    private String baseRef;

    /**
     * 按仓库指定的基线分支名映射（repositoryId → 分支名），支持多仓库各自不同的基准分支。
     * 解析优先级：{@code baseRefs} 中该仓库的值 &gt; 公共 {@code baseRef} &gt; 该仓库项目绑定的
     * defaultBranch（后端 Worker provision 兜底）。不接受 commit SHA、Git 引用路径或非法分支名。
     */
    @Size(max = 20)
    @Schema(description = "按仓库指定的基线分支映射（repositoryId → 分支名）；缺省回退公共 baseRef 或该仓库默认分支")
    private Map<UUID, String> baseRefs;

    @Schema(description = "交付模式：DIFF_FIRST/MR_FIRST；不传则自动判定（Planner/规则），续作沿用源任务")
    private String deliveryMode;
}
