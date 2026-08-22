package qg.qgent.github;

import java.util.List;

/**
 * GitHub Pull Request 提交列表及其真实总数。
 *
 * @param totalCount GitHub Pull Request 返回的提交总数
 * @param items 当前请求页内的提交
 */
public record GitHubPullRequestCommitList(int totalCount, List<GitHubPullRequestCommitDetails> items) {
}
