package qg.qgent.github;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GitHub Issue Comment 请求。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GitHubPullRequestCommentRequest(String body) {
}
