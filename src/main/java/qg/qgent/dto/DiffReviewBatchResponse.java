package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Task-level multi-repository Diff review summary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffReviewBatchResponse {
    private String id;
    private String taskId;
    private String reviewStatus;
    /**
     * 交付授权来源：USER=用户确认；SYSTEM=MR_FIRST 系统自动授权。
     * 前端据此决定显示「已由用户确认」还是「自动交付」，并隐藏 MR_FIRST 的确认按钮；
     * 绝不能把 SYSTEM 展示为「用户已确认」。
     */
    private String confirmationSource;
    private String deliveryStatus;
    private String aggregateHash;
    private String reviewReason;
    private List<DiffListItemResponse> diffs;
    private List<DiffRepositoryDeliveryResponse> repositoryDeliveries;
}
