package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 某个 Task 仓库当前提交创建 MR 前的真实门禁快照。
 * status 仅由服务端根据当前 source/target 提交和持久化执行事实计算。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreflightGateResponse {
    private String taskId;
    private String repositoryId;
    private String sourceCommit;
    private String targetBranch;
    private String targetCommit;
    /** PENDING / PASSED / FAILED。 */
    private String status;
    private List<String> blockers;
    private DryRunSummary dryRun;
    private CqSummary cqPlusOne;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DryRunSummary {
        private String id;
        private String status;
        private String sourceCommit;
        private String targetCommit;
        private String completedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CqSummary {
        /** PENDING / APPROVED / REJECTED。 */
        private String status;
        private String reviewerUserId;
        private String reason;
        private String reviewedAt;
    }
}
