package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MR 列表摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestSummaryResponse {
    private String id;
    private String repositoryId;
    private List<String> groupIds;
    private String provider;
    private Long number;
    private String sourceBranch;
    private String targetBranch;
    private String status;
    /** 合并操作状态：RUNNING、COMPLETED、FAILED；未发起时为 null。 */
    private String mergeOperationStatus;
    /** 异步合并失败时返回的受控错误码与原因。 */
    private String mergeOperationFailureCode;
    private String mergeOperationFailureReason;
    private String headCommit;
    /**
     * GitHub 是否可合并；null 表示 GitHub 尚未计算完成。
     */
    private Boolean mergeable;
    /**
     * GitHub mergeable_state 枚举：clean/dirty/blocked/behind/unstable/draft/unknown。
     */
    private String mergeableState;
    private QualityGateResponse qualityGate;
    private String title;
    private String webUrl;
    private String createdAt;
    /**
     * 关联的 Task ID。仅任务驱动的真实 MR 或待创建占位记录返回。
     */
    private String taskId;
    /**
     * MR 创建来源：MANUAL、SYSTEM 或 UNKNOWN。
     */
    private String createMode;
}
