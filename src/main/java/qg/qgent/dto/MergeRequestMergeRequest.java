package qg.qgent.dto;

import lombok.Data;

/**
 * GitHub squash 合并时可选的合并提交说明。
 */
@Data
public class MergeRequestMergeRequest {
    private String commitMessage;
}
