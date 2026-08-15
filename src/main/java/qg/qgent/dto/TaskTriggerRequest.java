package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 从群消息触发 Task 的请求（点7：聊天消息到 Agent Task 转换）。
 * <p>
 * 显式触发端点 {@code POST .../messages/{messageId}/trigger-task} 使用；
 * 缺省字段由服务端从触发消息/群信息提取（标题截断 255、需求用消息文本或群描述、
 * 仓库用群关联仓库）。
 */
@Data
public class TaskTriggerRequest {

    /**
     * Task 标题；缺省时服务端从消息文本或群标题提取（截断 255）。
     */
    @NotBlank
    @Size(max = 255)
    @Schema(description = "Task 标题；缺省用消息文本或群标题", maxLength = 255,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    /**
     * Task 需求描述；可空，缺省用消息文本或群描述。
     */
    @Size(max = 10000)
    @Schema(description = "Task 需求描述；缺省用消息文本或群描述", maxLength = 10000)
    private String requirement;

    /**
     * 新 Workspace 的仓库绑定 ID；可空，缺省用群关联仓库。
     */
    @Size(max = 20)
    @Schema(description = "新 Workspace 的仓库绑定 ID；缺省用群关联仓库")
    private List<@jakarta.validation.constraints.NotNull UUID> repositoryIds;

    /**
     * 可选公共基线分支。
     */
    @Size(max = 512)
    @Schema(description = "可选公共基线分支")
    private String baseRef;
}
