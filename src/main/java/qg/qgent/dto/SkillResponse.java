package qg.qgent.dto;

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

    /** Skill ID。 */
    private String id;

    /** 所属项目 ID。 */
    private String projectId;

    /** Skill 名称。 */
    private String name;

    /** 可复用操作规范正文。 */
    private String content;

    /** 标签列表。 */
    private List<String> tags;

    /** 可见性：PRIVATE / PROJECT_SHARED。 */
    private String visibility;

    /** 状态：DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED。 */
    private String status;

    /** 创建者摘要。 */
    private UserSummary creator;

    /** 最近审核者摘要，可为空。 */
    private UserSummary reviewer;

    /** 最近驳回原因，可为空。 */
    private String rejectionReason;

    /** 最近审核时间（UTC），可为空。 */
    private LocalDateTime reviewedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
