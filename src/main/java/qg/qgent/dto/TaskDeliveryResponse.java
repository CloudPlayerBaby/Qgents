package qg.qgent.dto;
import lombok.*;
import java.util.List;
/** Task-level delivery view containing repository-specific Deliverable items. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDeliveryResponse {
    private String id;
    private String taskId;
    private String projectId;
    private Integer version;
    private String status;
    private String reviewedBy;
    private String reviewReason;
    private String reviewedAt;
    private List<DeliverableResponse> items;
}
