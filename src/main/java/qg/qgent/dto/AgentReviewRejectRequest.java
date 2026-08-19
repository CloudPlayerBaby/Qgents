package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent 发布审核拒绝请求：拒绝原因可选，但建议填写便于创建者修正后重新提交。
 */
@Data
public class AgentReviewRejectRequest {
    /**
     * 拒绝原因（可选，最长 1000 字符；写入 reviewReason，仅创建者可见）。
     */
    @Size(max = 1000)
    @Schema(description = "拒绝原因（可选，最长 1000 字符）", maxLength = 1000)
    private String reason;
}
