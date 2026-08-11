package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 项目与 GitHub 仓库绑定。
 * 同一 GitHub 仓库镜像可被多个项目绑定，defaultBranch 可在项目内覆盖。
 */
@Data
@TableName("project_repositories")
public class ProjectRepositoryEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** GitHub 仓库镜像ID。 */
    private UUID repositoryId;
    /** 该项目使用的默认分支，可覆盖 GitHub 仓库默认值。 */
    private String defaultBranch;
    /** 仓库在项目内的显示名称。 */
    private String displayName;
    private LocalDateTime boundAt;
}
