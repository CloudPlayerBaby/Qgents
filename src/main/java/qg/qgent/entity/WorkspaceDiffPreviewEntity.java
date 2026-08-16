package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Workspace 实时 Diff Preview 头：一个 Workspace 一行，记录最新预览修订号。
 * 与正式 Diff（diffs/diff_review_batches）严格分离：preview 只反映 Coding 写过程中的
 * 累积工作树变更，永不被当作已 commit/push/MR。
 */
@Data
@TableName("workspace_diff_previews")
public class WorkspaceDiffPreviewEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 预览归属 Workspace（唯一）。
     */
    private UUID workspaceId;
    /**
     * 所属项目 ID。
     */
    private UUID projectId;
    /**
     * 最近一次写入预览的 Task。
     */
    private UUID taskId;
    /**
     * 最新预览修订号，修订单调递增。
     */
    private Long latestRevision;
    private LocalDateTime updatedAt;
}
