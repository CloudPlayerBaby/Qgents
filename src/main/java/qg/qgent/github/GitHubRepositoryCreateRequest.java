package qg.qgent.github;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Server-side payload for GitHub's repository creation endpoint.
 * {@code autoInit} 为 true 时由 GitHub 生成初始提交（README）并据此建立默认分支，
 * 使新建仓库在创建完成时即具备真实的 defaultBranch，避免空仓库缺少默认分支。
 */
public record GitHubRepositoryCreateRequest(
        /* 仓库名，需符合 GitHub 命名约束（小写字母、数字、-、_、.）。 */
        String name,
        /* 可选仓库描述。 */
        String description,
        /* 仓库是否私有；团队内部开发默认 true。 */
        @JsonProperty("private") boolean isPrivate,
        /* 是否生成初始提交以建立默认分支。 */
        @JsonProperty("auto_init") boolean autoInit
) {
}
