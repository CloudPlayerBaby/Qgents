package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 提交 MR 创建前 CQ 审查决定。 */
@Data
public class PreflightCqDecisionRequest {
    @Size(max = 1000)
    @Schema(description = "CQ 审查理由或拒绝修改意见，最多 1000 字符")
    private String reason;
}
