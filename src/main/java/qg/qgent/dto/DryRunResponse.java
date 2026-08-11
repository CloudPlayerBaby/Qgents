package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 试运行响应。状态枚举：QUEUED/RUNNING/PASSED/FAILED/CANCELLED。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DryRunResponse {
    private String id;
    private String projectId;
    private String repositoryId;
    private String workPackageId;
    private String sourceRef;
    private String targetBranch;
    private String status;
    private Map<String, Object> report;
    private String createdBy;
    private String createdAt;
}
