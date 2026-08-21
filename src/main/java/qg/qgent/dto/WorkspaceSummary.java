package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务关联 Workspace 展示摘要（任务详情使用）。
 * <p>
 * status 为 Workspace 生命周期状态（PROVISIONING/READY/LEASED/ARCHIVED/FAILED）；
 * repositories 为 Planner 已确定的实际写入仓库摘要；规划尚未物化时为空，
 * 不把 Workspace 的候选仓库范围直接暴露为任务目标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceSummary {

    /**
     * Workspace ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "Workspace ID")
    private String id;

    /**
     * Workspace 生命周期状态。
     */
    @Schema(description = "Workspace 状态")
    private String status;

    /**
     * Workspace 内仓库摘要列表。
     */
    @Schema(description = "Workspace 内仓库摘要")
    private List<RepositorySummary> repositories;
}
