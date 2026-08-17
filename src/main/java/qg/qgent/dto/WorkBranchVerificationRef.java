package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 针对工作分支当前已知提交执行的真实测试摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "工作分支当前提交的最近验证")
public class WorkBranchVerificationRef {
    @Schema(description = "验证类型，当前仅为 TEST_RUN")
    private String kind;
    @Schema(description = "测试执行状态")
    private String status;
    @Schema(description = "测试实际执行的不可变提交 SHA")
    private String commitSha;
    @Schema(description = "测试完成时间，UTC RFC 3339")
    private String completedAt;
}
