package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 测试运行响应。
 * 状态枚举：QUEUED/RUNNING/PASSED/FAILED/CANCELLED。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestRunResponse {
    private String id;
    private String projectId;
    private String repositoryId;
    private String ref;
    private List<String> testsetIds;
    private String status;
    private Map<String, Object> summary;
    private String createdBy;
    private String createdAt;
    /** 开始执行时间（ISO-8601，UTC）；QUEUED 时为空。 */
    private String startedAt;
    /** 结束执行时间（ISO-8601，UTC）；非终态时为空。 */
    private String finishedAt;
    /** 最后更新时间（ISO-8601，UTC）。 */
    private String updatedAt;
}
