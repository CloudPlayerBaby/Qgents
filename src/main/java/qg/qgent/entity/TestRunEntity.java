package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A controlled repository test execution, optionally requested by a TaskStep.
 */
@Data
@TableName(value = "test_runs", autoResultMap = true)
public class TestRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID taskId;
    private UUID taskStepId;
    private UUID projectRepositoryId;
    /**
     * Target commit or branch; Task-scoped runs are located by taskId and repositoryId.
     */
    private String ref;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> testsetIds;
    /**
     * 创建时固化的 Testset 执行定义，后续配置修改不会改变本次运行。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> executionSnapshot;
    /**
     * Worker 使用的不可变 Git 引用；Task 运行也通过临时 checkout 执行。
     */
    private String executionSourceRef;
    /**
     * Task 当前未提交工作树在 Worker 中固化出的隔离 Workspace；ref 运行为空。
     */
    private UUID executionWorkspaceId;
    private String status;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;
    private String claimToken;
    private LocalDateTime leaseExpiresAt;
    private Integer attemptCount;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
