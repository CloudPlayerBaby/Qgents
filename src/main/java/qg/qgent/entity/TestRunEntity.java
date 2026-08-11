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

/** A controlled repository test execution, optionally requested by a TaskStep. */
@Data
@TableName(value = "test_runs", autoResultMap = true)
public class TestRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID taskId;
    private UUID taskStepId;
    private UUID projectRepositoryId;
    /** Target commit or branch; Task-scoped runs are located by taskId and repositoryId. */
    private String ref;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> testsetIds;
    private String status;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
