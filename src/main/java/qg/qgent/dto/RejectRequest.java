package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 拒绝并给出原因请求（契约 §8/§9/§12/§13 通用）。
 */
@Data
public class RejectRequest {

    /** 驳回原因（必填）。 */
    @NotBlank
    @Size(max = 2048)
    private String reason;
}
