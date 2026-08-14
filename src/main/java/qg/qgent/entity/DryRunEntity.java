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
 * 合并前试运行。
 * 真实报告由执行服务写入 report，客户端只能读取。
 * 状态枚举：QUEUED/RUNNING/PASSED/FAILED/CANCELLED。
 */
@Data
@TableName(value = "dry_runs", autoResultMap = true)
public class DryRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** Task whose repository worktree is used for the merge simulation. */
    private UUID taskId;
    /** Task step requesting this dry run, when workflow-triggered. */
    private UUID taskStepId;
    /** 项目仓库绑定ID。 */
    private UUID projectRepositoryId;
    /** 客户端请求且可恢复调度使用的源分支或提交引用。 */
    private String sourceRef;
    /** Worker 解析后的确定提交 SHA；QUEUED 时为空。 */
    private String headCommit;
    /** 首次受理时解析并固定的目标分支 commit SHA，重试不得重新解析分支。 */
    private String resolvedTargetCommit;
    /** 目标分支名。 */
    private String targetBranch;
    /** 运行状态，取值见类注释。 */
    private String status;
    /** 试运行报告 JSON，含冲突与测试摘要。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> report;
    /** 目标分支门禁在创建时固化的 Testset 执行定义。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<Map<String, Object>> testsetSnapshot;
    private String claimToken;
    private LocalDateTime leaseExpiresAt;
    private Integer attemptCount;
    /** 发起用户ID。 */
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
