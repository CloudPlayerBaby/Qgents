package qg.qgent.dto;

import lombok.Data;

/**
 * 批准或拒绝 WAITING_APPROVAL 审批请求。例如 {"reason":"允许在受控 Sandbox 内执行测试"}。
 */
@Data
public class InputDecisionRequest {
    /** 审批或拒绝理由，可为空。 */
    private String reason;
}
