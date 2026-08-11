package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Diff 详情响应：变更统计与关联提交。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffResponse {
    private String id;
    private String projectId;
    private String deliverableId;
    private String repositoryId;
    private String baseRef;
    private String headRef;
    private String headCommit;
    private Map<String, Object> changeStats;
    private String createdAt;
}
