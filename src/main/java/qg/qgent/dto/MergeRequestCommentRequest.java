package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * MR 普通评论请求。行级评论属于 Diff 审查能力，不在本接口范围内。
 */
@Data
public class MergeRequestCommentRequest {
    @NotBlank
    @Size(max = 10000)
    @Schema(description = "评论正文", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 10000)
    private String body;
}
