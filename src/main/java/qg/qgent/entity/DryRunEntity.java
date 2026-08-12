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
    /** 源分支或提交引用。 */
    private String headCommit;
    /** 目标分支名。 */
    private String targetBranch;
    /** 运行状态，取值见类注释。 */
    private String status;
    /** 试运行报告 JSON，含冲突与测试摘要。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> report;
    /** 发起用户ID。 */
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
