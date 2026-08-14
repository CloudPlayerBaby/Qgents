package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 已授权的最终 Diff 原始 patch 快照，不包含宿主机存储位置。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffReviewPatchResponse {
    private String diffId;
    private String repositoryId;
    private String patch;
}
