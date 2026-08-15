package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GitHub Webhook 投递幂等记录。
 * 以 X-GitHub-Delivery 作为唯一键防止重复处理；状态流转为 RECEIVED -> PROCESSED/IGNORED/FAILED。
 * 只保存原始 body 的 SHA-256 摘要用于审计，不保存完整 payload 或任何 Secret。
 */
@Data
@TableName("github_webhook_deliveries")
public class GitHubWebhookDeliveryEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * X-GitHub-Delivery，GitHub 投递唯一标识。
     */
    private String providerDeliveryId;
    /**
     * X-GitHub-Event 事件名。
     */
    private String eventName;
    /**
     * payload 中的 action，可空。
     */
    private String action;
    /**
     * GitHub installation.id，可空。
     */
    private Long providerInstallationId;
    /**
     * GitHub repository.id，可空。
     */
    private Long providerRepositoryId;
    /**
     * 原始 body SHA-256 摘要（十六进制）。
     */
    private String payloadSha256;
    /**
     * 处理状态：RECEIVED/PROCESSED/IGNORED/FAILED。
     */
    private String status;
    /**
     * 最近失败码，不写入 Secret 或完整 payload。
     */
    private String failureCode;
    /**
     * 同一 delivery 实际处理次数。
     */
    private Integer attemptCount;
    /**
     * 接收时间（UTC）。
     */
    private LocalDateTime receivedAt;
    /**
     * 处理完成时间（UTC）。
     */
    private LocalDateTime processedAt;
    private LocalDateTime updatedAt;
}
