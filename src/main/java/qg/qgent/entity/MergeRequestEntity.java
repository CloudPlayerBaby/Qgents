package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GitHub Pull Request 业务镜像。
 * 由第 13 节受控接口触发服务端操作创建/同步；客户端不持有 GitHub 凭据。
 * 状态枚举：OPEN/MERGED/CLOSED；qualityGateStatus 枚举：PENDING/PASSED/FAILED。
 */
@Data
@TableName("merge_requests")
public class MergeRequestEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属项目仓库绑定ID。
     */
    private UUID projectRepositoryId;
    private UUID taskId;
    private UUID workspaceId;
    /**
     * 代码托管提供方枚举：GITHUB。
     */
    private String provider;
    /**
     * GitHub Pull Request真实编号。
     */
    private Long providerNumber;
    /**
     * 源分支名。
     */
    private String sourceBranch;
    /**
     * 目标分支名。
     */
    private String targetBranch;
    /**
     * 当前 MR 头提交 SHA。
     */
    private String headCommit;
    /**
     * GitHub 是否可合并；null 表示 GitHub 尚未计算完成。
     */
    private Boolean mergeable;
    /**
     * GitHub mergeable_state 枚举：clean/dirty/blocked/behind/unstable/draft/unknown。
     */
    private String mergeableState;
    /**
     * 合并基线（目标分支）提交 SHA，GitHub 冲突计算基于该提交。
     */
    private String baseSha;
    /**
     * MR 标题。
     */
    private String title;
    /**
     * MR 状态，取值见类注释。
     */
    private String status;
    /**
     * 门禁汇总状态，取值见类注释。
     */
    private String qualityGateStatus;
    private String mergeOperationId;
    private String mergeOperationStatus;
    private LocalDateTime mergeLeaseExpiresAt;
    /**
     * GitHub 侧更新时间（UTC）。
     */
    private LocalDateTime providerUpdatedAt;
    /**
     * 本地最近同步时间（UTC）。
     */
    private LocalDateTime syncedAt;
    /**
     * MR 作者用户ID，用于 CQ 权限校验。
     */
    private UUID authorUserId;
    private LocalDateTime createdAt;
}
