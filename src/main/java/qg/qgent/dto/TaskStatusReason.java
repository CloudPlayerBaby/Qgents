package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务运行等待/阻塞/失败原因摘要（TaskRun 详情与列表统一提供）。
 * <p>
 * code 枚举：INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/CANCELLED；
 * summary 仅含脱敏用户可见文本，技术堆栈、内部命令、凭据不得进入；retryable 由后端按状态机判断。
 * occurredAt 为原因发生时间（UTC），可为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusReason {

    /** 原因码。 */
    @Schema(description = "原因码")
    private String code;

    /** 一行标题，如“等待用户输入”。 */
    @Schema(description = "一行标题")
    private String title;

    /** 脱敏用户可见摘要。 */
    @Schema(description = "脱敏摘要")
    private String summary;

    /** 当前是否可重试。 */
    @Schema(description = "当前是否可重试")
    private boolean retryable;

    /** 原因发生时间（UTC），可为 null。 */
    @Schema(description = "原因发生时间")
    private String occurredAt;
}
