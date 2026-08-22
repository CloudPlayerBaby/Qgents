package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MR 提交记录列表。totalCount 来自 GitHub Pull Request 的真实提交计数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestCommitListResponse {
    @Schema(description = "GitHub Pull Request 的提交总数")
    private int totalCount;

    @Schema(description = "当前请求返回的提交记录")
    private List<MergeRequestCommitResponse> items;
}
