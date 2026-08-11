package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 目标分支质量门禁汇总状态。
 * status 枚举：PENDING/PASSED/FAILED。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityGateResponse {
    private String status;
    private List<String> requiredChecks;
}
