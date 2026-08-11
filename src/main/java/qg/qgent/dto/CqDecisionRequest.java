package qg.qgent.dto;

import lombok.Data;

/**
 * CQ+1 或 CQ 拒绝请求。审查者必须记录理由；拒绝理由用于退回修改意见。
 */
@Data
public class CqDecisionRequest {
    /** CQ 理由或修改意见。 */
    private String reason;
}
