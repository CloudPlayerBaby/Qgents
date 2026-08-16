package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Workspace 实时 Diff Preview 的一条修订：一次成功写后累积工作树 diff 的不可变快照元数据。
 * snapshotKey 指向受控存储的 patch 全文；workingTreeHash 是工作树变更摘要，用作幂等键。
 * 与正式 Diff 严格分离，永不作为已 commit/push/MR。
 */
@Data
@TableName("workspace_diff_preview_revisions")
public class WorkspaceDiffPreviewRevisionEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属项目 ID。
     */
    private UUID projectId;
    /**
     * 所属 Task。
     */
    private UUID taskId;
    /**
     * 产出该修订的 TaskRun（Coding 相位非空）。
     */
    private UUID taskRunId;
    /**
     * 预览归属 Workspace。
     */
    private UUID workspaceId;
    /**
     * 单调递增修订号（同 Workspace 内）。
     */
    private Long revision;
    /**
     * Diff 基准 commit。
     */
    private String baseCommit;
    /**
     * 工作树变更摘要哈希（幂等键）。
     */
    private String workingTreeHash;
    /**
     * 受控 patch 快照 key。
     */
    private String snapshotKey;
    /**
     * 变更文件数。
     */
    private Integer filesChanged;
    /**
     * 新增行数。
     */
    private Integer additions;
    /**
     * 删除行数。
     */
    private Integer deletions;
    private LocalDateTime createdAt;
}
