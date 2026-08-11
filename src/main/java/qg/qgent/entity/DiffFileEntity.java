package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Diff 内单个文件的变更摘要。
 * 以 Diff 内单调递增的 sequenceNo 作为游标供分页续读。
 * changeType 枚举：ADDED/MODIFIED/DELETED/RENAMED。
 */
@Data
@TableName(value = "diff_files", autoResultMap = true)
public class DiffFileEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属 Diff ID。 */
    private UUID diffId;
    /** Diff 内单调递增文件序号，用于游标分页。 */
    private Long sequenceNo;
    /** 文件路径。 */
    private String path;
    /** 变更类型，取值见类注释。 */
    private String changeType;
    /** 新增行数。 */
    private Integer additions;
    /** 删除行数。 */
    private Integer deletions;
    /** 是否二进制文件：0 否 / 1 是。 */
    private Boolean binaryFlag;
    /** hunk 摘要 JSON 数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> hunks;
    private LocalDateTime createdAt;
}
