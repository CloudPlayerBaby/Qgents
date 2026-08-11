package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * 需求群与项目仓库绑定的多对多关联实体，对应表 requirement_group_repositories。
 * <p>
 * 复合主键 (requirement_group_id, project_repository_id)，由自定义 SQL 维护（无单列主键）。
 */
@Data
@TableName("requirement_group_repositories")
public class RequirementGroupRepositoryEntity {

    /** 需求群 ID（UUIDv7，BINARY(16)）。 */
    private UUID requirementGroupId;

    /** 项目仓库绑定 ID（UUIDv7，BINARY(16)），指向 project_repositories 表。 */
    private UUID projectRepositoryId;
}
