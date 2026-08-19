package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务运行等待/阻塞/失败原因摘要（TaskRun 详情与列表统一提供）。
 * <p>
 * code 枚举：INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/STARTUP_FAILED/CANCELLED；
 * summary 仅含脱敏用户可见文本，技术堆栈、内部命令、凭据不得进入；retryable 由后端按状态机判断。
 * occurredAt 为原因发生时间（UTC），可为 null。
 */
@Data
public class TaskStatusReason {

    /**
     * 原因码。
     */
    @Schema(description = "原因码")
    private String code;

    /**
     * 失败的稳定技术分类码。仅 FAILED 时返回，供前端选择可读提示与重试策略；
     * 不包含原始异常、命令或凭据。
     */
    @Schema(description = "失败原因稳定码，仅 FAILED 时返回")
    private String failureCode;

    /**
     * 一行标题，如“等待用户输入”。
     */
    @Schema(description = "一行标题")
    private String title;

    /**
     * 脱敏用户可见摘要。
     */
    @Schema(description = "脱敏摘要")
    private String summary;

    /**
     * 当前是否可重试。
     */
    @Schema(description = "当前是否可重试")
    private boolean retryable;

    /**
     * 原因发生时间（UTC），可为 null。
     */
    @Schema(description = "原因发生时间")
    private String occurredAt;

    public TaskStatusReason() {
    }

    public TaskStatusReason(String code, String title, String summary, boolean retryable, String occurredAt) {
        this(code, null, title, summary, retryable, occurredAt);
    }

    public TaskStatusReason(String code, String failureCode, String title, String summary,
                            boolean retryable, String occurredAt) {
        this.code = code;
        this.failureCode = failureCode;
        this.title = title;
        this.summary = summary;
        this.retryable = retryable;
        this.occurredAt = occurredAt;
    }
}
