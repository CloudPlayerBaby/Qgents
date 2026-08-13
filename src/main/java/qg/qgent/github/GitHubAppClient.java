package qg.qgent.github;

import java.util.List;
import java.util.UUID;

/**
 * GitHub App 受控访问端口。实现只能使用服务端配置的 GitHub App 凭据，不得暴露安装令牌或私钥。
 */
public interface GitHubAppClient {
    /**
     * 为指定团队生成短时有效的 GitHub App 安装跳转地址。
     *
     * @param teamId 接收 GitHub App 授权的团队 ID
     * @param actorId 发起授权的已认证用户 ID
     * @return 包含签名 state 的 GitHub App 安装地址
     */
    String createInstallationUrl(UUID teamId, UUID actorId);

    /**
     * 验证 GitHub 回调携带的安装 state，并取得其绑定的团队。
     *
     * @param state GitHub 原样返回的签名 state
     * @return state 中的团队 ID
     */
    UUID verifyInstallationState(String state);

    /**
     * 查询单个 GitHub App 安装的元数据。
     *
     * @param installationId GitHub 提供的安装数字 ID
     * @return 安装元数据，不含安装访问令牌
     */
    GitHubInstallationDetails getInstallation(long installationId);

    /**
     * 生成短期有效的 GitHub App Installation Token。
     * 
     * @param installationId GitHub 提供的安装数字 ID
     * @return Installation Token
     */
    String createInstallationToken(long installationId);

    /**
     * 查询某个安装授权范围内的仓库元数据。
     *
     * @param installationId GitHub 提供的安装数字 ID
     * @return 已授权仓库列表，不含克隆地址或访问令牌
     */
    List<GitHubRepositoryDetails> listRepositories(long installationId);

    /**
     * 创建 Pull Request
     *
     * @param installationId 安装 ID
     * @param owner          仓库所有者
     * @param repo           仓库名称
     * @param request        创建 PR 请求参数
     * @return PR 详情
     */
    GitHubPullRequestDetails createPullRequest(long installationId, String owner, String repo, GitHubPullRequestCreateRequest request);

    /**
     * Gets branch details from GitHub, including the current HEAD SHA.
     *
     * @param installationId 安装 ID
     * @param owner          仓库所有者
     * @param repo           仓库名称
     * @param branch         分支名称
     * @return Branch 详情
     */
    GitHubBranchDetails getBranch(long installationId, String owner, String repo, String branch);

    /**
     * Gets the current Pull Request state and source/target refs from GitHub.
     */
    GitHubPullRequestDetails getPullRequest(long installationId, String owner, String repo, int pullNumber);

    /**
     * Gets check runs for a specific commit SHA. The result is GitHub data, not a Qgents quality-gate decision.
     */
    List<GitHubCheckRunDetails> getPullRequestChecks(long installationId, String owner, String repo, String headSha);

    /**
     * Gets GitHub reviews associated with the Pull Request.
     */
    List<GitHubReviewDetails> getPullRequestReviews(long installationId, String owner, String repo, int pullNumber);

    /**
     * Requests a GitHub merge and returns GitHub's explicit outcome. Callers must not mark a local MR merged when
     * {@link GitHubPullRequestMergeResult#merged()} is false.
     */
    GitHubPullRequestMergeResult mergePullRequest(long installationId, String owner, String repo, int pullNumber,
            GitHubPullRequestMergeRequest request);
}
