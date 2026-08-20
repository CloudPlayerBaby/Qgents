package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * 申请分支级 MR 预检的请求体。source/target branch、head/target SHA 均由服务端从 Workspace
 * 与 Git Store 读取，客户端不得覆盖，因此本 DTO 只携带任务与仓库标识。
 */
@Data
public class MergeRequestPreflightRequest {
    /** 触发申请的任务；分支级汇总可为任一已交付任务。 */
    @NotNull
    @Schema(description = "触发预检申请的任务ID")
    private UUID taskId;

    /** 项目仓库绑定ID；多仓库任务逐仓库分别申请。 */
    @NotNull
    @Schema(description = "项目仓库绑定ID")
    private UUID repositoryId;
}
