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
public class TestRunResponse {
    private String id;
    private String projectId;
    private String repositoryId;
    /** 关联的 Task ID；普通按 ref 发起的测试为空。 */
    private String taskId;
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
    /** 后端基于 UTC 生命周期时间计算的运行时长（毫秒）；尚未开始或时间异常时为空。 */
    private Long durationMs;

    /**
     * 保留旧构造签名，避免已有控制器/测试代码因响应新增字段而无法编译。
     */
    public TestRunResponse(String id, String projectId, String repositoryId, String ref,
                           List<String> testsetIds, String status, Map<String, Object> summary,
                           String createdBy, String createdAt, String startedAt, String finishedAt,
                           String updatedAt, Long durationMs) {
        this(id, projectId, repositoryId, null, ref, testsetIds, status, summary, createdBy,
                createdAt, startedAt, finishedAt, updatedAt, durationMs);
    }

    public TestRunResponse(String id, String projectId, String repositoryId, String taskId, String ref,
                           List<String> testsetIds, String status, Map<String, Object> summary,
                           String createdBy, String createdAt, String startedAt, String finishedAt,
                           String updatedAt, Long durationMs) {
        this.id = id;
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.taskId = taskId;
        this.ref = ref;
        this.testsetIds = testsetIds;
        this.status = status;
        this.summary = summary;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.updatedAt = updatedAt;
        this.durationMs = durationMs;
    }
}
