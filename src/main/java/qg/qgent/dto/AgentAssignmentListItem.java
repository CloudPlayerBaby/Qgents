package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 分配列表项（契约 v1.8.0 §20，成员 B B04）。
 * <p>
 * type=REQUIREMENT_GROUP 时 resourceId 为需求群 ID；type=WORKFLOW 当前无数据源，返回空列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAssignmentListItem {

    /**
     * 分配类型：REQUIREMENT_GROUP / WORKFLOW。
     */
    @Schema(description = "分配类型：REQUIREMENT_GROUP / WORKFLOW")
    private String type;

    /**
     * 资源 ID（需求群 ID）。
     */
    @Schema(description = "资源 ID")
    private String resourceId;

    /**
     * 资源名称。
     */
    @Schema(description = "资源名称")
    private String resourceName;

    /**
     * 状态：ACTIVE / INACTIVE。
     */
    @Schema(description = "状态：ACTIVE / INACTIVE")
    private String status;
}
