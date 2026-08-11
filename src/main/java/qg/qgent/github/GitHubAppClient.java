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
     * 查询某个安装授权范围内的仓库元数据。
     *
     * @param installationId GitHub 提供的安装数字 ID
     * @return 已授权仓库列表，不含克隆地址或访问令牌
     */
    List<GitHubRepositoryDetails> listRepositories(long installationId);
}
