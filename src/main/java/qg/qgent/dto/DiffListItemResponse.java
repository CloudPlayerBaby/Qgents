package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Diff 列表项摘要：供任务中心/Diff 列表展示，包含产出归属、分支提交与变更统计。
 * 完整 hunk 与文件明细通过 Diff 详情与 /files 接口获取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffListItemResponse {
    private String id;
    private String projectId;
    private String taskId;
    private String taskRunId;
    private String taskStepId;
    private String requirementGroupId;
    private String workspaceId;
    private String repositoryId;
    private String baseCommit;
    private String sourceBranch;
    private String headCommit;
    private String status;
    private Map<String, Object> changeStats;
    private String createdAt;
}
