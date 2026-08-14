package qg.qgent.github;

/**
 * GitHub 分支详情，包含分支名和 HEAD commit SHA
 */
public record GitHubBranchDetails(String name, String commitSha) {
}
