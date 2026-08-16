package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import qg.qgent.entity.GitHubInstallationEntity;

@Mapper
public interface GitHubInstallationMapper extends BaseMapper<GitHubInstallationEntity> {

    /**
     * 按 provider installation id 原子领取安装记录（行锁）。
     * 不同 X-GitHub-Delivery 的事件（如 added 与 suspend）通过该行锁按 Installation 串行，
     * 避免「added 读取 ACTIVE + 拉取列表」与「suspend 撤销仓库」交错导致撤销后被重新授权。
     */
    @Select("SELECT * FROM github_installations WHERE provider_installation_id = #{providerInstallationId} FOR UPDATE")
    GitHubInstallationEntity selectByProviderInstallationIdForUpdate(
            @Param("providerInstallationId") Long providerInstallationId);
}
