package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.MergeRequestCommentRequest;
import qg.qgent.dto.MergeRequestCommentResponse;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MergeRequestCommentEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubPullRequestCommentDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.MergeRequestCommentMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * MR 普通评论服务。
 * <p>
 * 评论先由受控 GitHub App 写入真实 Pull Request 对应的 Issue 讨论，成功后才写入本地镜像；
 * 不在事务或数据库锁持有期间调用 GitHub。Diff 的行级评论仍由 Diff 接口负责。
 */
@Service
public class MergeRequestCommentService {
    private final MergeRequestMapper mergeRequests;
    private final MergeRequestCommentMapper comments;
    private final ProjectRepositoryMapper projectRepositories;
    private final GitHubRepositoryMapper githubRepositories;
    private final GitHubInstallationMapper installations;
    private final GitHubAppClient github;
    private final ProjectAccessService access;
    private final EventService events;
    private final TransactionTemplate transactions;

    public MergeRequestCommentService(MergeRequestMapper mergeRequests, MergeRequestCommentMapper comments,
                                      ProjectRepositoryMapper projectRepositories,
                                      GitHubRepositoryMapper githubRepositories,
                                      GitHubInstallationMapper installations, GitHubAppClient github,
                                      ProjectAccessService access, EventService events,
                                      TransactionTemplate transactions) {
        this.mergeRequests = mergeRequests;
        this.comments = comments;
        this.projectRepositories = projectRepositories;
        this.githubRepositories = githubRepositories;
        this.installations = installations;
        this.github = github;
        this.access = access;
        this.events = events;
        this.transactions = transactions;
    }

    public List<MergeRequestCommentResponse> list(UUID projectId, UUID mergeRequestId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        return comments.selectList(Wrappers.<MergeRequestCommentEntity>lambdaQuery()
                        .eq(MergeRequestCommentEntity::getMergeRequestId, mr.getId())
                        .orderByAsc(MergeRequestCommentEntity::getCreatedAt))
                .stream().map(this::response).toList();
    }

    public MergeRequestCommentResponse add(UUID projectId, UUID mergeRequestId, UUID actor,
                                           MergeRequestCommentRequest request) {
        access.requireProjectMember(projectId, actor);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        if (!"OPEN".equals(mr.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_COMMENT_NOT_ALLOWED",
                    "只有 OPEN 状态的 MR 可以添加评论");
        }
        if (mr.getProviderNumber() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_PROVIDER_REFERENCE_MISSING",
                    "MR 尚未绑定真实 GitHub 编号，暂时不能评论");
        }
        String body = request == null || request.getBody() == null ? null : request.getBody().trim();
        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MR_COMMENT_BODY_REQUIRED", "评论正文不能为空");
        }
        ProjectRepositoryEntity binding = projectRepositories.selectById(mr.getProjectRepositoryId());
        if (binding == null || !projectId.equals(binding.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "MR 所属仓库不存在");
        }
        GitHubRepositoryEntity repository = githubRepositories.selectById(binding.getRepositoryId());
        if (repository == null || repository.getInstallationId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_NOT_READY", "MR 仓库授权信息不可用");
        }
        GitHubInstallationEntity installation = installations.selectById(repository.getInstallationId());
        if (installation == null || installation.getProviderInstallationId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_NOT_READY", "GitHub 安装授权不可用");
        }

        // 外部调用在事务外执行；本地镜像只接受 GitHub 成功返回的评论事实。
        GitHubPullRequestCommentDetails remote = github.createPullRequestComment(
                installation.getProviderInstallationId(), repository.getOwnerLogin(), repository.getName(),
                Math.toIntExact(mr.getProviderNumber()),
                new qg.qgent.github.GitHubPullRequestCommentRequest(body));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MergeRequestCommentEntity entity = new MergeRequestCommentEntity();
        entity.setId(UuidV7.next());
        entity.setMergeRequestId(mr.getId());
        entity.setAuthorUserId(actor);
        entity.setProviderCommentId(Long.toString(remote.id()));
        entity.setBody(remote.body() == null ? body : remote.body());
        entity.setWebUrl(remote.htmlUrl());
        entity.setCreatedAt(now);
        MergeRequestCommentEntity stored = inTransaction(() -> {
            comments.insert(entity);
            return entity;
        });
        events.publish(projectId, null, "merge-request.comment.created", mr.getId().toString(),
                java.util.Map.of("mergeRequestId", mr.getId(), "commentId", entity.getId(),
                        "providerCommentId", entity.getProviderCommentId()));
        return response(stored);
    }

    private MergeRequestEntity requireMr(UUID projectId, UUID mergeRequestId) {
        MergeRequestEntity mr = mergeRequests.selectById(mergeRequestId);
        if (mr == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或无权访问");
        }
        ProjectRepositoryEntity binding = projectRepositories.selectById(mr.getProjectRepositoryId());
        if (binding == null || !projectId.equals(binding.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或无权访问");
        }
        return mr;
    }

    private MergeRequestCommentResponse response(MergeRequestCommentEntity value) {
        return new MergeRequestCommentResponse(value.getId().toString(), value.getMergeRequestId().toString(),
                value.getAuthorUserId().toString(), value.getProviderCommentId(), value.getBody(), value.getWebUrl(),
                value.getCreatedAt() == null ? null : value.getCreatedAt().atOffset(ZoneOffset.UTC).toString());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }
}
