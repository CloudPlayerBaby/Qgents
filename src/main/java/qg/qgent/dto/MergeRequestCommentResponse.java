package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MR 评论响应，字段同时覆盖普通评论和行级评论。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestCommentResponse {
    private String id;
    private String mergeRequestId;
    private String authorUserId;
    private String providerCommentId;
    private String body;
    private String webUrl;
    private String createdAt;
}
