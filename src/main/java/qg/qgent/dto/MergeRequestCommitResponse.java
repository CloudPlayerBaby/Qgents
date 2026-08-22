package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MR 提交记录。数据来自 GitHub Pull Request commits 接口，不由本地 headCommit 推测。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestCommitResponse {
    @Schema(description = "Git 提交 SHA")
    private String sha;

    @Schema(description = "GitHub 返回的提交说明")
    private String message;

    @Schema(description = "提交作者显示名")
    private String authorName;

    @Schema(description = "Qgents 作者用户 ID；GitHub 作者未建立受控身份映射时为 null", nullable = true)
    private String authorUserId;

    @Schema(description = "GitHub 记录的提交时间，ISO-8601 UTC")
    private String committedAt;
}
