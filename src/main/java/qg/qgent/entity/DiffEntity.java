package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 交付关联的 Diff 元数据与变更统计。
 * 行级内容由 diff_files 按文件摘要承载，完整行级 hunk 由受控服务按需提供。
 */
@Data
@TableName(value = "diffs", autoResultMap = true)
public class DiffEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** 关联交付物ID，可为空。 */
    private UUID deliverableId;
    /** 项目仓库绑定ID。 */
    private UUID projectRepositoryId;
    /** Diff 基线引用。 */
    private String baseRef;
    /** Diff 头引用。 */
    private String headRef;
    /** Diff 对应的头提交SHA。 */
    private String headCommit;
    /** 变更统计 JSON，如文件数、增删行数。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> changeStats;
    private LocalDateTime createdAt;
}
