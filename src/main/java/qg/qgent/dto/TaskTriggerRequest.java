package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从群消息触发 Task 的请求（点7：聊天消息到 Agent Task 转换）。
 * <p>
 * 显式触发端点 {@code POST .../messages/{messageId}/trigger-task} 使用；
 * 缺省字段由服务端从触发消息/群信息提取（标题截断 255、需求用消息文本或群描述、
 * 仓库用群关联仓库）。
 * <p>
 * 续作字段（workspaceId / continuationOfTaskId）不由客户端提交：当触发消息直接回复
 * {@code message_type=DIFF} 的消息时，服务端从该 Diff 的 {@code content.diffId} 推导源
 * Task 与 Workspace 并自动续作；普通引用仍创建新 Workspace。
 */
@Data
public class TaskTriggerRequest {

    /**
     * Task 标题；可空，缺省时服务端从触发消息文本或群标题提取（截断 255）。
     */
    @Size(max = 255)
    @Schema(description = "Task 标题；可空，缺省用触发消息文本或群标题", maxLength = 255)
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
     * 可选公共基线分支名。可空：缺省时各仓库按项目仓库绑定记录的 defaultBranch 独立解析
     * （Git Store 同步与建树均使用同一解析值）；不接受 commit SHA、Git 引用路径或非法分支名。
     */
    @Size(max = 512)
    @Schema(description = "可选公共基线分支；缺省按各仓库的 defaultBranch 独立解析")
    private String baseRef;

    /**
     * 按仓库指定的基线分支名映射（repositoryId → 分支名），支持多仓库各自不同的基准分支。
     * 解析优先级：{@code baseRefs} 中该仓库的值 &gt; 公共 {@code baseRef} &gt; 该仓库项目绑定的
     * defaultBranch（后端 Worker provision 兜底）。不接受 commit SHA、Git 引用路径或非法分支名。
     */
    @Size(max = 20)
    @Schema(description = "按仓库指定的基线分支映射；缺省回退公共 baseRef 或该仓库默认分支")
    private Map<UUID, String> baseRefs;

    /**
     * 交付模式：DIFF_FIRST/MR_FIRST；不传则自动判定（Planner/规则），续作沿用源任务。
     */
    @Schema(description = "交付模式：DIFF_FIRST/MR_FIRST；不传则自动判定")
    private String deliveryMode;
}
