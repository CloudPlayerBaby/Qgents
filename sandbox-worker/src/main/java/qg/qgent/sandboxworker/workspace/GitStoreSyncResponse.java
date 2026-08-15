package qg.qgent.sandboxworker.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Git Store 同步完成后的受控状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Git Store 同步结果")
public class GitStoreSyncResponse {
    @Schema(description = "仓库编号")
    private UUID repositoryId;

    @Schema(description = "已同步的远程分支")
    private String remoteBranch;

    @Schema(description = "已核验的远程 HEAD SHA")
    private String headCommit;

    @Schema(description = "本次是否新建 bare Git Store")
    private boolean created;
}
