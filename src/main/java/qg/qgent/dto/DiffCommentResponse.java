package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Diff 审查意见响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffCommentResponse {
    private String id;
    private String diffId;
    private String path;
    private String side;
    private Integer line;
    private String hunkId;
    private String commitSha;
    private String body;
    private String authorUserId;
    private String authorName;
    /**
     * 评论作者头像 URL（用户头像，可为 null）；供前端评论列表直接展示，无需再查成员列表。
     */
    private String authorAvatarUrl;
    private String createdAt;
}
