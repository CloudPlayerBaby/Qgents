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

/** Immutable Task-level Diff snapshot and its change statistics. */
@Data
@TableName(value = "diffs", autoResultMap = true)
public class DiffEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** Task that produced this final Diff. */
    private UUID taskId;
    /** 产出该 Diff 的任务运行ID；受控执行服务落库，未绑定运行前为空。 */
    private UUID taskRunId;
    /** 产出该 Diff 的任务步骤ID；受控执行服务落库，未绑定步骤前为空。 */
    private UUID taskStepId;
    private UUID workspaceId;
    /** 项目仓库绑定ID。 */
    private UUID projectRepositoryId;
    /** Diff 基线引用。 */
    private String baseCommit;
    /** Diff 头引用。 */
    private String sourceBranch;
    private String workingTreeHash;
    private String snapshotKey;
    /** Diff 对应的头提交SHA。 */
    private String headCommit;
    private String status;
    private UUID reviewedBy;
    private String reviewReason;
    private LocalDateTime reviewedAt;
    private UUID reviewBatchId;
    private String deliveryStatus;
    /** 稳定的失败分类码，供接口和重试策略使用。 */
    private String deliveryFailureCode;
    private String deliveryFailureReason;
    private String deliveryOperationId;
    /** 每次领取交付时重新生成的 fencing token，旧执行者不得写回。 */
    private String deliveryClaimToken;
    private LocalDateTime deliveryLeaseExpiresAt;
    /** 变更统计 JSON，如文件数、增删行数。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> changeStats;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
