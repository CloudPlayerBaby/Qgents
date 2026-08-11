package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * 基于已接受交付物创建 MR 请求。
 * 服务端从交付物取得仓库、源分支与提交 SHA，不接受客户端提交的 GitHub Token、提交 SHA 或门禁结果。
 */
@Data
public class MergeRequestCreateRequest {
    /** 已接受的交付物ID。 */
    @NotNull
    private UUID deliverableId;
    /** 目标分支名。 */
    @NotBlank
    private String targetBranch;
    /** MR 标题。 */
    @NotBlank
    private String title;
}
