package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Test Run 列表项。列表只返回运行身份、筛选维度和生命周期时间，不携带测试结果详情。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestRunListItemResponse {
    private String id;
    private String projectId;
    private String repositoryId;
    private List<String> testsetIds;
    private String taskId;
    private String ref;
    private String status;
    private String createdBy;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
}
