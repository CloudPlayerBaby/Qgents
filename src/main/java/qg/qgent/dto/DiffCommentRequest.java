package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加 Diff 审查意见请求。
 * 行级评论应包含 side 与 line；hunk 级评论使用 hunkId。评论绑定当前 Diff 头提交。
 */
@Data
public class DiffCommentRequest {
    /**
     * 评论指向的文件路径。
     */
    @NotBlank
    private String path;
    /**
     * 变更侧：LEFT/RIGHT，行级评论可指定。
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
     * 审查意见正文。
     */
    @NotBlank
    private String body;
}
