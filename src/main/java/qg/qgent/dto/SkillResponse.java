package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 视图（契约 §8）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillResponse {

    /**
     * Skill ID。
     */
    @Schema(description = "Skill ID")
    private String id;

    /**
     * 所属项目 ID。
     */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /**
     * Skill 名称。
     */
    @Schema(description = "Skill 名称")
    private String name;

    /**
     * 可复用操作规范正文。
     */
    @Schema(description = "可复用操作规范正文")
    private String content;

    /**
     * 标签列表。
     */
    @Schema(description = "标签列表")
    private List<String> tags;

    /**
     * 可见性：PRIVATE / PROJECT_SHARED。
     */
    @Schema(description = "可见性：PRIVATE / PROJECT_SHARED")
    private String visibility;

    /**
     * 状态：DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED。
     */
    @Schema(description = "状态：DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED")
    private String status;

    /**
     * 创建者摘要。
     */
    @Schema(description = "创建者摘要")
    private UserSummary creator;

    /**
     * 最近审核者摘要，可为空。
     */
    @Schema(description = "最近审核者摘要")
    private UserSummary reviewer;

    /**
     * 最近驳回原因，可为空。
     */
    @Schema(description = "最近驳回原因")
    private String rejectionReason;

    /**
     * 最近审核时间（UTC），可为空。
     */
    @Schema(description = "最近审核时间（UTC）")
    private LocalDateTime reviewedAt;

    /**
     * 创建时间（UTC）。
     */
    @Schema(description = "创建时间（UTC）")
    private LocalDateTime createdAt;

    /**
     * 更新时间（UTC）。
     */
    @Schema(description = "更新时间（UTC）")
    private LocalDateTime updatedAt;
}
