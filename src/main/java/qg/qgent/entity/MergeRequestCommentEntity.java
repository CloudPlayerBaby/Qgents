package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Qgents 创建的 MR 评论镜像。评论正文和定位事实来自 GitHub 成功响应后才落库。
 */
@Data
@TableName("merge_request_comments")
public class MergeRequestCommentEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID mergeRequestId;
    private UUID authorUserId;
    private String providerCommentId;
    private String body;
    private String webUrl;
    private LocalDateTime createdAt;
}
