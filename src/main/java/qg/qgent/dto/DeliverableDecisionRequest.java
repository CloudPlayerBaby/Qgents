package qg.qgent.dto;

import lombok.Data;

/**
 * 接受或拒绝交付物请求。拒绝时必须给出退回原因。
 */
@Data
public class DeliverableDecisionRequest {
    /** 接受时可为空；拒绝时必填退回原因。 */
    private String reason;
}
