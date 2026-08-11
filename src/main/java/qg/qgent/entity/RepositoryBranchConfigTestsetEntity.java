package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * 分支质量门禁与强制测试集关系（复合主键，无独立主键列）。
 * 受保护分支必选的测试集不可被普通成员绕过或跳过。
 */
@Data
@TableName("repository_branch_config_testsets")
public class RepositoryBranchConfigTestsetEntity {
    /** 仓库分支配置ID。 */
    private UUID branchConfigId;
    /** 该分支门禁强制执行的测试集ID。 */
    private UUID testsetId;
}
