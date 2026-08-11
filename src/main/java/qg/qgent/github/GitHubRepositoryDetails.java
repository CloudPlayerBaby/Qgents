package qg.qgent.github;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub App 授权仓库的服务端查询结果，不包含克隆凭据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepositoryDetails {
    /** GitHub 提供的仓库数字 ID。 */
    private long repositoryId;

    /** 仓库所有者登录名。 */
    private String ownerLogin;

    /** 仓库名称。 */
    private String name;

    /** 仓库默认分支。 */
    private String defaultBranch;

    /** 仓库可见性。 */
    private String visibility;

    /** 仓库是否已归档。 */
    private boolean archived;
}
