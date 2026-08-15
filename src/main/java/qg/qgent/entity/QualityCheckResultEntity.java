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
 * MR 真实质量门禁执行结果。
 * checkType 枚举：TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE；
 * 状态枚举：PENDING/RUNNING/PASSED/FAILED。
 * 检查结果由服务端执行/同步服务或 CQ 端点写入，客户端不得直接回写。
 */
@Data
@TableName(value = "quality_check_results", autoResultMap = true)
public class QualityCheckResultEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属 MR ID。
     */
    private UUID mergeRequestId;
    /**
     * 检查类型，取值见类注释。
     */
    private String checkType;
    /**
     * 相同提交和检查类型的执行序号，从 1 开始。
     */
    private Integer attemptNo;
    /**
     * 执行状态，取值见类注释。
     */
    private String status;
    /**
     * TESTSET 检查使用的测试集ID。
     */
    private UUID testsetId;
    /**
     * 检查对应的 Git 提交 SHA。
     */
    private String commitSha;
    /**
     * 结果来源服务标识。
     */
    private String source;
    /**
     * 检查摘要 JSON，包含统计、失败项和脱敏日志引用。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> summary;
    /**
     * 开始执行时间（UTC）。
     */
    private LocalDateTime startedAt;
    /**
     * 完成执行时间（UTC）。
     */
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
