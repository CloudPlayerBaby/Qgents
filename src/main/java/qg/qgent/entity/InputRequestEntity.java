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
 * 任务运行期间的人机输入 / 审批请求。
 * kind 枚举：INPUT/APPROVAL；status 枚举：PENDING/ANSWERED/APPROVED/REJECTED/EXPIRED。
 * INPUT 请求通过 answer 回答，APPROVAL 请求通过 approve/reject 处理。
 */
@Data
@TableName(value = "input_requests", autoResultMap = true)
public class InputRequestEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属任务运行ID。 */
    private UUID taskRunId;
    /** 请求类型：INPUT/APPROVAL。 */
    private String kind;
    /** 请求状态，取值见类注释。 */
    private String status;
    /** 面向用户或审批人的问题描述。 */
    private String prompt;
    /** 可选答案选项 JSON 数组，未提供时为空。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> options;
    /** INPUT 类型的用户回答 JSON。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> answer;
    /** 审批/拒绝理由或回复备注。 */
    private String reason;
    /** 发起请求的用户ID（一般为编排发起人）。 */
    private UUID createdBy;
    private LocalDateTime createdAt;
    /** 回答/审批/拒绝处理时间（UTC），未处理时为空。 */
    private LocalDateTime resolvedAt;
}
