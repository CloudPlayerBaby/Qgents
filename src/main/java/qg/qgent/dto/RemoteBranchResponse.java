package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub 远程分支摘要。该对象描述真实远程引用，不代表 Qgents 的 Task 工作分支。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "GitHub 远程分支摘要")
public class RemoteBranchResponse {
    @Schema(description = "分支名", example = "develop")
    private String name;

    @Schema(description = "分支当前 HEAD 提交 SHA", example = "0123456789abcdef0123456789abcdef01234567")
    private String commitSha;

    @Schema(description = "是否为 GitHub 仓库全局默认分支")
    private boolean githubDefault;

    @Schema(description = "是否为当前 Qgents 项目的默认基准分支")
    private boolean projectDefault;
}
