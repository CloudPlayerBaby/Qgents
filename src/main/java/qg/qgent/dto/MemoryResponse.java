package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Memory 视图（契约 §9）。
 * <p>
 * 响应必须包含 creator、reviewer、reviewedAt、category、tags 与 sources。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryResponse {

    /** Memory ID。 */
    private String id;

    /** 所属项目 ID。 */
    private String projectId;

    /** 知识标题。 */
    private String title;

    /** 经确认的项目事实正文。 */
    private String content;

    /** 知识分类标识。 */
    private String category;

    /** 标签列表。 */
    private List<String> tags;

    /** 状态：DRAFT / PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED。 */
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

    /** 来源消息引用列表（AI 草稿有值，手动创建为空）。 */
    private List<MemorySourceRef> sources;
}
