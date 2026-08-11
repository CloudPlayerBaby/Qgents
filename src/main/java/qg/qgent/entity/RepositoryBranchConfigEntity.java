package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目仓库按分支配置的保护和质量门禁策略。
 * requiredChecks 为必需门禁类型 JSON 数组，可含 TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE。
 */
@Data
@TableName(value = "repository_branch_configs", autoResultMap = true)
public class RepositoryBranchConfigEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目仓库绑定ID。 */
    private UUID projectRepositoryId;
    /** 应用配置的目标分支名。 */
    private String branchName;
    /** 分支保护策略 JSON，如合并限制和命名规则。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> policyJson;
    /** 必需门禁类型 JSON 字符串数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> requiredChecks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
