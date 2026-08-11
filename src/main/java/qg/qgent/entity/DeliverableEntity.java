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

/**
 * 受控执行产出的交付物。
 * 创建由受控执行服务完成，客户端不得伪造其关联的提交、测试结果或 Diff。
 * 状态枚举：PENDING_REVIEW/ACCEPTED/REJECTED。
 */
@Data
@TableName(value = "deliverables", autoResultMap = true)
public class DeliverableEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** Task whose repository-scoped result this deliverable represents. */
    private UUID taskId;
    /** Task step that produced this deliverable, when known. */
    private UUID taskStepId;
    /** Overall Task delivery aggregate containing this repository-specific item. */
    private UUID taskDeliveryId;
    /** 关联需求群ID，可为空。 */
    private UUID requirementGroupId;
    /** @deprecated Legacy read-only compatibility anchor; new writes use taskId and taskDeliveryId. */
    /** 产出交付物的任务运行ID，可为空。 */
    private UUID taskRunId;
    /** 项目仓库绑定ID。 */
    private UUID projectRepositoryId;
    /** 交付变更所在源分支。 */
    private String sourceBranch;
    /** 交付提交SHA。 */
    private String headCommit;
    /** 交付摘要 JSON，如变更统计和检查摘要。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;
    /** 交付状态，取值见类注释。 */
    private String status;
    /** 发起用户ID。 */
    private UUID createdBy;
    /** 最近审查用户ID，未审查时为空。 */
    private UUID reviewedBy;
    /** 接受/拒绝原因。 */
    private String reviewReason;
    /** 审查时间（UTC）。 */
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
