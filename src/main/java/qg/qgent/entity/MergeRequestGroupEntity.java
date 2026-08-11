package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * MR 与多个需求群的关系（复合主键，无独立主键列）。
 * 关联表不使用 BaseMapper，查询与写入由专用 Mapper 方法承担。
 */
@Data
@TableName("merge_request_groups")
public class MergeRequestGroupEntity {
    /** MR 镜像ID。 */
    private UUID mergeRequestId;
    /** 关联的需求群ID。 */
    private UUID requirementGroupId;
}
