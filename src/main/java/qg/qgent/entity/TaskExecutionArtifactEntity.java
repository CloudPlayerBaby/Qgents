package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** Task timeline entry produced by planning or a controlled TaskRun. */
@Data
@TableName(value = "task_execution_artifacts", autoResultMap = true)
public class TaskExecutionArtifactEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID taskId;
    private UUID taskRunId;
    private UUID taskStepId;
    private Integer sequenceNo;
    private String artifactType;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;
    private LocalDateTime createdAt;
}
