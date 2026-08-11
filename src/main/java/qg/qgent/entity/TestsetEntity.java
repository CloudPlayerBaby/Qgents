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
 * 项目自定义测试集。
 * 本期只管理配置，实际执行由后续系统承担。
 * 状态枚举：ENABLED/DISABLED。
 */
@Data
@TableName(value = "testsets", autoResultMap = true)
public class TestsetEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID。 */
    private UUID projectId;
    /** 限定的项目仓库绑定ID；为空表示项目通用。 */
    private UUID projectRepositoryId;
    /** 测试集名称。 */
    private String name;
    /** 测试定义 JSON，包含命令、超时和通过条件。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> definition;
    /** 状态：ENABLED/DISABLED。 */
    private String status;
    /** 创建用户ID。 */
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
