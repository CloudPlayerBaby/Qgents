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
    private String createdAt;
}
