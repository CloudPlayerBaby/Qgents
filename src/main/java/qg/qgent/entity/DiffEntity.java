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
    /** 变更统计 JSON，如文件数、增删行数。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> changeStats;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
