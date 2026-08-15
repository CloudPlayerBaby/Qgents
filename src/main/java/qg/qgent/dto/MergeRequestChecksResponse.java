package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MR 质量门禁检查汇总（契约 §13 / §21）。
 * <p>
 * 包装扁平检查列表为前端进度图所需形状：顶层 status 与 qualityGate.status 一致
 * （PENDING/PASSED/FAILED，PASSED 当且仅当全部检查 PASSED）；requiredChecks 只含
 * TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE 四项；items 为逐检查详情。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestChecksResponse {

    /**
     * 门禁总状态：PENDING / PASSED / FAILED（与 qualityGate.status 一致）。
     */
    @Schema(description = "门禁总状态：PENDING / PASSED / FAILED")
    private String status;

    /**
     * 必选检查清单：TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE。
     */
    @Schema(description = "必选检查清单")
    private List<String> requiredChecks;

    /**
     * 逐检查详情（status：PENDING/PASSED/FAILED）。
     */
    @Schema(description = "逐检查详情")
    private List<MergeRequestCheckResponse> items;
}
