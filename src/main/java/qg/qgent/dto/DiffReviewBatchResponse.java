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
    private String deliveryStatus;
    private String aggregateHash;
    private String reviewReason;
    private List<DiffListItemResponse> diffs;
    private List<DiffRepositoryDeliveryResponse> repositoryDeliveries;
}
