package qg.qgent.sandboxworker.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 tool_executions 表的工具执行持久化实体。
 */
@Data
@TableName("tool_executions")
public class ToolExecutionEntity {
    @TableId
    private String id;
    private String ownerWorkerId;
    private String sandboxId;
    private String repositoryId;
    private String toolName;
    private String argumentsJson;
    private String status;
    private Integer exitCode;
    private String resultJson;
    private String failureCode;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
