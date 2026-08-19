package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dry Run 列表项。列表不返回报告、测试用例和 Sandbox 详情，详情通过报告接口读取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DryRunListItemResponse {
    private String id;
    private String projectId;
    private String repositoryId;
    private String sourceRef;
    private String targetBranch;
    private String taskId;
    private String status;
    private String createdBy;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
}
