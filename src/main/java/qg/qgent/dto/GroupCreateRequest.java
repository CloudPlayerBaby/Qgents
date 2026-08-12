package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 创建需求群请求（契约 §7）。
 * <p>
 * title 对应表字段 name；type 仅允许 REQUIREMENT 或省略，传入 PROJECT_MAIN 返回 422。
 */
@Data
public class GroupCreateRequest {

    /** 群标题（必填，≤255）。 */
    @NotBlank
    @Size(max = 255)
    @Schema(description = "群标题（必填）", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    /** 群目标和需求背景说明（≤4096，可空）。 */
    @Size(max = 4096)
    @Schema(description = "群目标和需求背景说明", maxLength = 4096)
    private String description;

    /** 关联的项目仓库绑定 ID 列表（可空）；每个 ID 必须已绑定到该项目。 */
    @Schema(description = "关联的项目仓库绑定 ID 列表，须已绑定到该项目")
    private List<UUID> repositoryIds;

    /** 群类型：REQUIREMENT 或省略；不允许 PROJECT_MAIN。 */
    @Size(max = 32)
    @Schema(description = "群类型：REQUIREMENT 或省略；传 PROJECT_MAIN 返回 422", maxLength = 32)
    private String type;
}
