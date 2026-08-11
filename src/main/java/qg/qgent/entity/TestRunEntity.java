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
 * 受控测试运行。
 * 仅管理配置与状态，真实执行由执行服务承担。
 * 状态枚举：QUEUED/RUNNING/PASSED/FAILED/CANCELLED。
 */
@Data
@TableName(value = "test_runs", autoResultMap = true)
public class TestRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** Task whose workspace/ref is tested, when task-triggered. */
    private UUID taskId;
    /** Task step requesting this run, when workflow-triggered. */
    private UUID taskStepId;
    /** 项目仓库绑定ID。 */
    private UUID projectRepositoryId;
    /** 关联工作包ID；第11节建表后补外键，可为空。 */
    /** @deprecated Legacy read-only compatibility anchor; new writes use taskId. */
    @Deprecated
    private UUID workPackageId;
    /** 目标提交或分支引用，与 workPackageId 二选一。 */
    private String ref;
    /** 启用测试集ID JSON 数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> testsetIds;
    /** 运行状态，取值见类注释。 */
    private String status;
    /** 用例与结果摘要 JSON。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;
    /** 发起用户ID。 */
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
