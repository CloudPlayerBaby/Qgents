package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Diff 行级或 hunk 级审查意见。
 * 行级评论应包含 side/line，hunk 级评论使用 hunkId；评论绑定 commitSha，
 * 避免 Diff 更新后评论指向错误代码。
 */
@Data
@TableName("diff_comments")
public class DiffCommentEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属 Diff ID。
     */
    private UUID diffId;
    /**
     * 评论指向的文件路径。
     */
    private String path;
    /**
     * 变更侧枚举：LEFT/RIGHT，可为空。
     */
    private String side;
    /**
     * 行号，行级评论必填。
     */
    private Integer line;
    /**
     * hunk 标识，hunk 级评论使用。
     */
    private String hunkId;
    /**
     * 评论绑定的提交SHA。
     */
    private String commitSha;
    /**
     * 审查意见正文。
     */
    private String body;
    /**
     * 评论作者用户ID。
     */
    private UUID authorUserId;
    private LocalDateTime createdAt;
}
