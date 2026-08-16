package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.github.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitPushRequest;
import qg.qgent.orchestration.worker.WorkerGitPushResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * MR 镜像、审查与质量门禁服务。
 * MR belongs to one repository and is derived from the persisted source branch
 * and head commit
 * of a Task Workspace. Client-supplied credentials, commit SHAs and gate
 * outcomes are not trusted.
 * qualityGate 汇总：从目标分支 branch config 的 required_checks + 必选测试集取必检项，
 * 对照 quality_check_results 在 headCommit 的最新 attempt_no；全部 PASSED → PASSED，
 * 任一 FAILED → FAILED，缺失或运行中 → PENDING。
 */
@Service
public class MergeRequestService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Duration MERGE_OPERATION_LEASE = Duration.ofMinutes(20);

    private final MergeRequestMapper mergeRequestMapper;
    private final MergeRequestGroupMapper mergeRequestGroupMapper;
    private final QualityCheckResultMapper qualityCheckMapper;
    private final MergeRequestReviewMapper reviewMapper;
    private final TaskMapper taskMapper;
    private final WorkspaceRepositoryMapper workspaceRepositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    private final GitHubInstallationMapper githubInstallationMapper;
    private final GitHubRepositoryMapper githubRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final GitHubAppClient githubClient;
    private final ProjectAccessService projectAccess;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final SandboxWorkerClient workerClient;
    private final GitCredentialService credentialService;
    private final MergeRequestDeliveryOperationMapper deliveryOperationMapper;
    private final TransactionTemplate transactions;
    private final DiffMapper diffMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public MergeRequestService(MergeRequestMapper mergeRequestMapper, MergeRequestGroupMapper mergeRequestGroupMapper,
                               QualityCheckResultMapper qualityCheckMapper, MergeRequestReviewMapper reviewMapper,
                               TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                               ProjectRepositoryMapper projectRepositoryMapper,
                               RepositoryBranchConfigMapper branchConfigMapper,
                               RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
                               EventService eventService, GitHubInstallationMapper githubInstallationMapper,
                               GitHubRepositoryMapper githubRepositoryMapper, ProjectMapper projectMapper, GitHubAppClient githubClient,
                               NotificationService notificationService, SandboxWorkerClient workerClient,
                               GitCredentialService credentialService, MergeRequestDeliveryOperationMapper deliveryOperationMapper,
                               TransactionTemplate transactions, DiffMapper diffMapper) {
        this.mergeRequestMapper = mergeRequestMapper;
        this.mergeRequestGroupMapper = mergeRequestGroupMapper;
        this.qualityCheckMapper = qualityCheckMapper;
        this.reviewMapper = reviewMapper;
        this.taskMapper = taskMapper;
        this.workspaceRepositoryMapper = workspaceRepositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.githubInstallationMapper = githubInstallationMapper;
        this.githubRepositoryMapper = githubRepositoryMapper;
        this.projectMapper = projectMapper;
        this.githubClient = githubClient;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.workerClient = workerClient;
        this.credentialService = credentialService;
        this.deliveryOperationMapper = deliveryOperationMapper;
        this.transactions = transactions;
        this.diffMapper = diffMapper;
    }

    /**
     * 兼容纯 Mockito 单元测试；生产环境始终使用带事务协调器的构造器。
     */
    MergeRequestService(MergeRequestMapper mergeRequestMapper, MergeRequestGroupMapper mergeRequestGroupMapper,
                        QualityCheckResultMapper qualityCheckMapper, MergeRequestReviewMapper reviewMapper,
                        TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                        ProjectRepositoryMapper projectRepositoryMapper, RepositoryBranchConfigMapper branchConfigMapper,
                        RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
                        EventService eventService, GitHubInstallationMapper githubInstallationMapper,
                        GitHubRepositoryMapper githubRepositoryMapper, ProjectMapper projectMapper, GitHubAppClient githubClient,
                        NotificationService notificationService, SandboxWorkerClient workerClient,
                        GitCredentialService credentialService) {
        this(mergeRequestMapper, mergeRequestGroupMapper, qualityCheckMapper, reviewMapper, taskMapper,
                workspaceRepositoryMapper, projectRepositoryMapper, branchConfigMapper, branchConfigTestsetMapper,
                projectAccess, eventService, githubInstallationMapper, githubRepositoryMapper, projectMapper,
                githubClient, notificationService, workerClient, credentialService, null, null, null);
    }

    /**
     * 查询项目关联 MR，支持仓库、需求群、状态过滤（游标分页）。
     */
    public ApiPageResponse<MergeRequestSummaryResponse> list(UUID projectId, UUID userId, UUID repositoryId,
                                                             UUID groupId, String status, String cursor, int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        int size = clampLimit(limit);
        List<UUID> repoIds = projectRepositoryMapper.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)).stream()
                .map(ProjectRepositoryEntity::getId).toList();
        if (repoIds.isEmpty()) {
            return emptyPage(requestId);
        }
        UUID cursorUuid = parseCursor(cursor);
        LambdaQueryWrapper<MergeRequestEntity> query = Wrappers.<MergeRequestEntity>lambdaQuery()
                .in(MergeRequestEntity::getProjectRepositoryId, repoIds)
                .eq(status != null && !status.isBlank(), MergeRequestEntity::getStatus, status)
                .eq(repositoryId != null, MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .lt(cursorUuid != null, MergeRequestEntity::getId, cursorUuid)
                .orderByDesc(MergeRequestEntity::getId)
                .last("LIMIT " + (size + 1));
        if (groupId != null) {
            List<UUID> mrIds = mergeRequestGroupMapper.selectByRequirementGroupId(groupId).stream()
                    .map(MergeRequestGroupEntity::getMergeRequestId).toList();
            if (mrIds.isEmpty()) {
                return emptyPage(requestId);
            }
            query.in(MergeRequestEntity::getId, mrIds);
        }
        List<MergeRequestEntity> rows = mergeRequestMapper.selectList(query);
        boolean hasMore = rows.size() > size;
        List<MergeRequestEntity> page = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, List<String>> groupIdsByMr = groupIdsByMr(page);
        Map<UUID, QualityGateResponse> gatesByMr = qualityGates(page);
        Map<UUID, String> webUrlsByMr = webUrlsByMr(page);
        List<MergeRequestSummaryResponse> items = page.stream()
                .map(mr -> toSummary(mr, groupIdsByMr.getOrDefault(mr.getId(), List.of()),
                        gatesByMr.get(mr.getId()), webUrlsByMr.get(mr.getId())))
                .toList();
        PageMeta meta = new PageMeta(hasMore ? items.get(items.size() - 1).getId() : null, hasMore);
        return new ApiPageResponse<>(items, meta, requestId);
    }

    /**
     * 查询 MR、关联需求群、检查与审查摘要，并汇总 qualityGate 状态。
     * 额外返回 webUrl（GitHub 地址）、diffId（关联的已接受 Diff）、description（当前为 null）。
     */
    public MergeRequestDetailResponse detail(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        List<String> groupIds = groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of());
        return toDetail(mr, groupIds, qualityGate(mr), mrWebUrl(mr), acceptedDiffId(mr));
    }

    /**
     * 以短事务领取、无事务外调、短事务落库的方式创建真实 Pull Request。
     */
    public MergeRequestSummaryResponse create(UUID projectId, UUID userId, MergeRequestCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        CreateClaim claim = claimCreateWithRetry(projectId, userId, request);
        if (claim.existing() != null) {
            if (claim.existing().getHeadCommit().equals(claim.worktree().getHeadCommit())) {
                return summary(claim.existing());
            }
            return pushAndUpdateExisting(claim);
        }
        try {
            GitHubPullRequestDetails remote = createRemote(claim);
            validateRemote(claim, remote);
            recordRemoteCreated(claim, remote);
            MergeRequestEntity mr = inTransaction(() -> finalizeCreate(claim, remote));
            publishUpdated(mr);
            return summary(mr);
        } catch (RuntimeException failure) {
            markCreateFailed(claim, failure);
            throw failure;
        }
    }

    private CreateClaim claimCreateWithRetry(UUID projectId, UUID userId, MergeRequestCreateRequest request) {
        try {
            return inTransaction(() -> claimCreate(projectId, userId, request));
        } catch (DuplicateKeyException race) {
            return inTransaction(() -> claimCreate(projectId, userId, request));
        }
    }

    private CreateClaim claimCreate(UUID projectId, UUID userId, MergeRequestCreateRequest request) {
        TaskEntity task = taskMapper.selectById(request.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        if (!userId.equals(task.getCreatedBy())) projectAccess.requireProjectAdmin(projectId, userId);
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectByIdForUpdate(request.getRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_IN_PROJECT",
                    "Repository is not bound to the current Project");
        }
        WorkspaceRepositoryEntity worktree = workspaceRepositoryMapper.selectForUpdate(task.getWorkspaceId(),
                request.getRepositoryId());
        if (worktree == null || worktree.getHeadCommit() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_BRANCH_NOT_PUSHED",
                    "The repository branch must have a committed head before MR creation");
        }
        requireAcceptedDelivery(task, worktree, request.getRepositoryId());
        MergeRequestEntity existing = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, request.getRepositoryId())
                .eq(MergeRequestEntity::getSourceBranch, worktree.getSourceBranch())
                .eq(MergeRequestEntity::getTargetBranch, request.getTargetBranch())
                .eq(MergeRequestEntity::getStatus, "OPEN").orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (existing != null && worktree.getHeadCommit().equals(existing.getHeadCommit())) {
            return new CreateClaim(task, worktree, null, null, request, null, null, existing);
        }
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, request.getRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        if (existing != null) {
            // 已有 open MR 且 headCommit 不同：推送新 commit 后更新已有 MR，不新建 PR，也不走 delivery operation
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, existing);
        }
        if (deliveryOperationMapper == null) {
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, null);
        }
        MergeRequestDeliveryOperationEntity active = deliveryOperationMapper.selectActiveBranchForUpdate(
                request.getRepositoryId(), worktree.getSourceBranch(), request.getTargetBranch());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (active != null && active.getLeaseExpiresAt() != null && active.getLeaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_CREATION_IN_PROGRESS",
                    "Pull Request creation is already in progress for this branch");
        }
        if (active != null) {
            active.setStatus("FAILED");
            active.setFailureCode("MR_CREATION_LEASE_EXPIRED");
            active.setClaimToken(null);
            active.setLeaseExpiresAt(null);
            active.setUpdatedAt(now);
            deliveryOperationMapper.updateById(active);
        }
        String key = operationKey(projectId, task, worktree, request);
        MergeRequestDeliveryOperationEntity operation = deliveryOperationMapper.selectByKeyForUpdate(key);
        if (operation != null) requireOperationContext(operation, projectId, task, worktree, request);
        if (operation != null && "COMPLETED".equals(operation.getStatus())) {
            MergeRequestEntity completed = mergeRequestMapper.selectById(operation.getMergeRequestId());
            if (completed != null) return new CreateClaim(task, worktree, githubRepository, installation,
                    request, operation, null, completed);
        }
        if (operation != null && (("RUNNING".equals(operation.getStatus())
                || "REMOTE_CREATED".equals(operation.getStatus()))
                && operation.getLeaseExpiresAt() != null && operation.getLeaseExpiresAt().isAfter(now))) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_CREATION_IN_PROGRESS", "Pull Request creation is already in progress");
        }
        String token = UUID.randomUUID().toString();
        if (operation == null) {
            operation = new MergeRequestDeliveryOperationEntity();
            operation.setId(UuidV7.next());
            operation.setOperationKey(key);
            operation.setProjectId(projectId);
            operation.setProjectRepositoryId(request.getRepositoryId());
            operation.setTaskId(task.getId());
            operation.setWorkspaceId(task.getWorkspaceId());
            operation.setActorUserId(userId);
            operation.setSourceBranch(worktree.getSourceBranch());
            operation.setTargetBranch(request.getTargetBranch());
            operation.setHeadCommit(worktree.getHeadCommit());
            operation.setTitle(request.getTitle());
            operation.setCreatedAt(now);
        }
        operation.setStatus("RUNNING");
        operation.setClaimToken(token);
        operation.setLeaseExpiresAt(now.plus(Duration.ofMinutes(20)));
        operation.setFailureCode(null);
        operation.setUpdatedAt(now);
        if (deliveryOperationMapper.selectById(operation.getId()) == null) deliveryOperationMapper.insert(operation);
        else deliveryOperationMapper.updateById(operation);
        return new CreateClaim(task, worktree, githubRepository, installation, request, operation, token, null);
    }

    /**
     * 已有 open MR 且 headCommit 不同：推送新 commit 到已有分支（GitHub 会自动更新该 PR），
     * 并同步本地 MR 镜像的 headCommit，不新建 PR。
     */
    private MergeRequestSummaryResponse pushAndUpdateExisting(CreateClaim claim) {
        GitHubRepositoryEntity github = claim.githubRepository();
        GitHubInstallationEntity installation = claim.installation();
        WorkspaceRepositoryEntity worktree = claim.worktree();
        String fullName = github.getOwnerLogin() + "/" + github.getName();
        String grantId = credentialService.generateGrant(installation.getTeamId(), claim.task().getProjectId(),
                installation.getProviderInstallationId(), fullName, worktree.getSourceBranch(),
                worktree.getHeadCommit(), GitCredentialPurpose.PUSH);
        WorkerGitPushResponse pushed;
        try {
            pushed = workerClient.pushWorkspaceBranch(claim.task().getWorkspaceId(), claim.request().getRepositoryId(),
                    new WorkerGitPushRequest().setExpectedHeadCommit(worktree.getHeadCommit())
                            .setCredentialGrantId(grantId));
        } catch (ApiException failure) {
            throw new ApiException(failure.status(), "WORKER_PUSH_FAILED",
                    "Failed to push branch via Sandbox Worker: " + failure.getMessage());
        }
        if (pushed == null || !pushed.isVerified() || !worktree.getHeadCommit().equals(pushed.getHeadCommit())) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKER_PUSH_VERIFICATION_FAILED",
                    "Sandbox Worker push verification failed or HEAD mismatch");
        }
        MergeRequestEntity existing = claim.existing();
        existing.setHeadCommit(worktree.getHeadCommit());
        existing.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        mergeRequestMapper.updateById(existing);
        publishUpdated(existing);
        return summary(existing);
    }

    private GitHubPullRequestDetails createRemote(CreateClaim claim) {
        GitHubRepositoryEntity github = claim.githubRepository();
        GitHubInstallationEntity installation = claim.installation();
        WorkspaceRepositoryEntity worktree = claim.worktree();
        MergeRequestCreateRequest request = claim.request();
        GitHubPullRequestDetails remote = githubClient.findOpenPullRequest(installation.getProviderInstallationId(),
                github.getOwnerLogin(), github.getName(), worktree.getSourceBranch(), request.getTargetBranch());
        if (remote != null) return remote;
        String fullName = github.getOwnerLogin() + "/" + github.getName();
        String grantId = credentialService.generateGrant(installation.getTeamId(), claim.task().getProjectId(),
                installation.getProviderInstallationId(), fullName, worktree.getSourceBranch(),
                worktree.getHeadCommit(), GitCredentialPurpose.PUSH);
        WorkerGitPushResponse pushed;
        try {
            pushed = workerClient.pushWorkspaceBranch(claim.task().getWorkspaceId(), request.getRepositoryId(),
                    new WorkerGitPushRequest().setExpectedHeadCommit(worktree.getHeadCommit())
                            .setCredentialGrantId(grantId));
        } catch (ApiException failure) {
            throw new ApiException(failure.status(), "WORKER_PUSH_FAILED",
                    "Failed to push branch via Sandbox Worker: " + failure.getMessage());
        }
        if (pushed == null || !pushed.isVerified() || !worktree.getHeadCommit().equals(pushed.getHeadCommit())) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKER_PUSH_VERIFICATION_FAILED",
                    "Sandbox Worker push verification failed or HEAD mismatch");
        }
        return githubClient.createPullRequest(installation.getProviderInstallationId(), github.getOwnerLogin(),
                github.getName(), new GitHubPullRequestCreateRequest(request.getTitle(), null,
                        worktree.getSourceBranch(), request.getTargetBranch()));
    }

    private void validateRemote(CreateClaim claim, GitHubPullRequestDetails remote) {
        if (remote == null || remote.number() <= 0 || remote.headSha() == null
                || !claim.worktree().getHeadCommit().equalsIgnoreCase(remote.headSha())
                || !claim.worktree().getSourceBranch().equals(remote.headBranch())
                || !claim.request().getTargetBranch().equals(remote.baseBranch())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_RESPONSE_INVALID",
                    "GitHub returned an invalid Pull Request response");
        }
    }

    private void recordRemoteCreated(CreateClaim claim, GitHubPullRequestDetails remote) {
        if (claim.operation() == null) return;
        inTransaction(() -> {
            MergeRequestDeliveryOperationEntity operation = deliveryOperationMapper.selectByIdForUpdate(claim.operation().getId());
            requireOperationClaim(operation, claim.token());
            operation.setStatus("REMOTE_CREATED");
            operation.setProviderNumber((long) remote.number());
            operation.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofMinutes(20)));
            operation.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            deliveryOperationMapper.updateById(operation);
            return null;
        });
    }

    private MergeRequestEntity finalizeCreate(CreateClaim claim, GitHubPullRequestDetails remote) {
        MergeRequestDeliveryOperationEntity operation = claim.operation() == null ? null
                : deliveryOperationMapper.selectByIdForUpdate(claim.operation().getId());
        if (operation != null) requireOperationClaim(operation, claim.token());
        MergeRequestEntity existing = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, claim.request().getRepositoryId())
                .eq(MergeRequestEntity::getProvider, "GITHUB")
                .eq(MergeRequestEntity::getProviderNumber, (long) remote.number()).last("LIMIT 1"));
        MergeRequestEntity mr = existing == null ? new MergeRequestEntity() : existing;
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            mr.setId(UuidV7.next());
            mr.setProjectRepositoryId(claim.request().getRepositoryId());
            mr.setTaskId(claim.task().getId());
            mr.setWorkspaceId(claim.task().getWorkspaceId());
            mr.setProvider("GITHUB");
            mr.setProviderNumber((long) remote.number());
            mr.setSourceBranch(remote.headBranch());
            mr.setTargetBranch(remote.baseBranch());
            mr.setHeadCommit(remote.headSha());
            mr.setTitle(remote.title() == null ? claim.request().getTitle() : remote.title());
            mr.setStatus(toLocalStatus(remote));
            mr.setQualityGateStatus("PENDING");
            mr.setSyncedAt(now);
            mr.setAuthorUserId(operation == null ? claim.task().getCreatedBy() : operation.getActorUserId());
            mr.setCreatedAt(now);
            mergeRequestMapper.insert(mr);
            if (claim.task().getRequirementGroupId() != null) {
                MergeRequestGroupEntity relation = new MergeRequestGroupEntity();
                relation.setMergeRequestId(mr.getId());
                relation.setRequirementGroupId(claim.task().getRequirementGroupId());
                mergeRequestGroupMapper.insert(relation);
            }
            refreshQualityGate(mr);
        }
        if (operation != null) {
            operation.setStatus("COMPLETED");
            operation.setMergeRequestId(mr.getId());
            operation.setProviderNumber((long) remote.number());
            operation.setClaimToken(null);
            operation.setLeaseExpiresAt(null);
            operation.setFailureCode(null);
            operation.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            deliveryOperationMapper.updateById(operation);
        }
        return mr;
    }

    private void markCreateFailed(CreateClaim claim, RuntimeException failure) {
        if (claim.operation() == null) return;
        try {
            inTransaction(() -> {
                MergeRequestDeliveryOperationEntity operation = deliveryOperationMapper.selectByIdForUpdate(claim.operation().getId());
                if (operation == null || !claim.token().equals(operation.getClaimToken())
                        || "COMPLETED".equals(operation.getStatus())) return null;
                operation.setStatus("FAILED");
                operation.setClaimToken(null);
                operation.setLeaseExpiresAt(null);
                operation.setFailureCode(failure instanceof ApiException api ? api.code() : "MR_CREATION_FAILED");
                operation.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryOperationMapper.updateById(operation);
                return null;
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void requireOperationClaim(MergeRequestDeliveryOperationEntity operation, String token) {
        if (operation == null || token == null || !token.equals(operation.getClaimToken())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_CREATION_CLAIM_LOST", "Pull Request creation claim was lost");
        }
    }

    private String operationKey(UUID projectId, TaskEntity task, WorkspaceRepositoryEntity worktree,
                                MergeRequestCreateRequest request) {
        try {
            String value = projectId + "\n" + task.getId() + "\n" + task.getWorkspaceId() + "\n"
                    + request.getRepositoryId() + "\n" + worktree.getSourceBranch() + "\n"
                    + request.getTargetBranch() + "\n" + worktree.getHeadCommit();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private void requireAcceptedDelivery(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId) {
        if (!"DIFF_FIRST".equals(task.getDeliveryMode())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_DELIVERY_MODE_INVALID",
                    "Only a confirmed DIFF_FIRST Task can create this Pull Request");
        }
        if (diffMapper == null || diffMapper.selectAcceptedCommittedForMr(task.getId(), task.getProjectId(),
                task.getWorkspaceId(), repositoryId, worktree.getHeadCommit()) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_REVIEWED_DIFF_REQUIRED",
                    "The current Workspace HEAD is not an accepted and committed Task Diff");
        }
    }

    private void requireOperationContext(MergeRequestDeliveryOperationEntity operation, UUID projectId,
                                         TaskEntity task, WorkspaceRepositoryEntity worktree, MergeRequestCreateRequest request) {
        if (!projectId.equals(operation.getProjectId()) || !task.getId().equals(operation.getTaskId())
                || !task.getWorkspaceId().equals(operation.getWorkspaceId())
                || !request.getRepositoryId().equals(operation.getProjectRepositoryId())
                || !worktree.getHeadCommit().equals(operation.getHeadCommit())
                || !worktree.getSourceBranch().equals(operation.getSourceBranch())
                || !request.getTargetBranch().equals(operation.getTargetBranch())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_OPERATION_CONTEXT_CHANGED",
                    "Pull Request delivery operation no longer belongs to this Task Workspace state");
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }

    private MergeRequestSummaryResponse summary(MergeRequestEntity mr) {
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    private record CreateClaim(TaskEntity task, WorkspaceRepositoryEntity worktree,
                               GitHubRepositoryEntity githubRepository, GitHubInstallationEntity installation,
                               MergeRequestCreateRequest request, MergeRequestDeliveryOperationEntity operation,
                               String token, MergeRequestEntity existing) {
    }

    private record MergeClaim(MergeRequestEntity mergeRequest, GitHubRepositoryEntity githubRepository,
                              GitHubInstallationEntity installation, String operationId, boolean alreadyCompleted) {
    }

    /**
     * 查询门禁检查汇总（契约 §21：包装为 {status, requiredChecks, items[]}）。
     */
    public MergeRequestChecksResponse checks(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        List<MergeRequestCheckResponse> items = qualityCheckMapper.selectList(Wrappers.<QualityCheckResultEntity>lambdaQuery()
                .eq(QualityCheckResultEntity::getMergeRequestId, mergeRequestId)
                .orderByAsc(QualityCheckResultEntity::getCreatedAt)).stream().map(this::toCheck).toList();
        QualityGateResponse gate = qualityGate(mr);
        return new MergeRequestChecksResponse(gate.getStatus(), gate.getRequiredChecks(), items);
    }

    /**
     * 查询人工与 AI 审查摘要。
     */
    public List<MergeRequestReviewResponse> reviews(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireMr(projectId, mergeRequestId);
        return reviewMapper.selectList(Wrappers.<MergeRequestReviewEntity>lambdaQuery()
                .eq(MergeRequestReviewEntity::getMergeRequestId, mergeRequestId)
                .orderByAsc(MergeRequestReviewEntity::getCreatedAt)).stream().map(this::toReview).toList();
    }

    /**
     * Refreshes the local mirror from GitHub's current Pull Request state.
     */
    public MergeRequestSummaryResponse sync(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, mr.getProjectRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        GitHubPullRequestDetails remote = githubClient.getPullRequest(installation.getProviderInstallationId(),
                githubRepository.getOwnerLogin(), githubRepository.getName(), requireProviderNumber(mr));
        mr = inTransaction(() -> persistRemoteState(projectId, mergeRequestId, remote));
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    /**
     * 提交一次 CQ+1 审查。
     * The MR author cannot review their own MR.
     */
    @Transactional
    public MergeRequestSummaryResponse cqApproval(UUID projectId, UUID mergeRequestId, UUID userId, String reason) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        requireCqReviewer(mr, userId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MergeRequestReviewEntity review = new MergeRequestReviewEntity();
        review.setId(UuidV7.next());
        review.setMergeRequestId(mr.getId());
        review.setReviewKind("HUMAN");
        review.setReviewerUserId(userId);
        review.setDecision("APPROVED");
        review.setSummary(reason);
        review.setReviewedAt(now);
        review.setCreatedAt(now);
        reviewMapper.insert(review);
        writeCheck(mr, "CQ_PLUS_ONE", "PASSED", "cq_approval", reason, now);
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    /**
     * 拒绝 CQ 并给出修改意见。
     * The MR author cannot reject their own MR.
     */
    @Transactional
    public MergeRequestSummaryResponse cqRejection(UUID projectId, UUID mergeRequestId, UUID userId, String reason) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        requireCqReviewer(mr, userId);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CQ_REJECTION_REASON_REQUIRED", "拒绝 CQ 必须给出修改意见");
        }
        writeCheck(mr, "CQ_PLUS_ONE", "FAILED", "cq_rejection", reason, LocalDateTime.now(ZoneOffset.UTC));
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    /**
     * 请求真实 GitHub 合并。数据库只在认领和落库阶段持有短事务，网络调用始终在事务外执行。
     */
    public MergeRequestSummaryResponse merge(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectAdmin(projectId, userId);
        MergeClaim claim = inTransaction(() -> claimMerge(projectId, mergeRequestId));
        if (claim.alreadyCompleted()) {
            return summary(claim.mergeRequest());
        }
        try {
            GitHubPullRequestDetails remote = githubClient.getPullRequest(
                    claim.installation().getProviderInstallationId(), claim.githubRepository().getOwnerLogin(),
                    claim.githubRepository().getName(), requireProviderNumber(claim.mergeRequest()));
            if (remote == null || !remote.merged()) {
                GitHubPullRequestMergeResult result = githubClient.mergePullRequest(
                        claim.installation().getProviderInstallationId(), claim.githubRepository().getOwnerLogin(),
                        claim.githubRepository().getName(), requireProviderNumber(claim.mergeRequest()),
                        new GitHubPullRequestMergeRequest(
                                "Merge " + (claim.mergeRequest().getTitle() == null
                                        ? "Pull Request" : claim.mergeRequest().getTitle()),
                                null, "squash", claim.mergeRequest().getHeadCommit()));
                if (!result.merged()) {
                    throw new ApiException(HttpStatus.CONFLICT, "GITHUB_MERGE_NOT_COMPLETED",
                            result.message() == null ? "GitHub did not merge the Pull Request" : result.message());
                }
            }
            MergeRequestEntity merged = inTransaction(() -> completeMerge(projectId, mergeRequestId,
                    claim.operationId()));
            publishUpdated(merged);
            return summary(merged);
        } catch (RuntimeException failure) {
            inTransaction(() -> {
                failMerge(mergeRequestId, claim.operationId());
                return null;
            });
            throw failure;
        }
    }

    private MergeClaim claimMerge(UUID projectId, UUID mergeRequestId) {
        MergeRequestEntity mr = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (mr == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, mr.getProjectRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        if ("MERGED".equals(mr.getStatus()) && "COMPLETED".equals(mr.getMergeOperationStatus())) {
            return new MergeClaim(mr, githubRepository, installation, mr.getMergeOperationId(), true);
        }
        if (!"OPEN".equals(mr.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_NOT_OPEN",
                    "Only an open Pull Request can be merged");
        }
        if (!"PASSED".equals(qualityGate(mr).getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "QUALITY_GATE_NOT_PASSED", "质量门禁未通过，无法合并");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("RUNNING".equals(mr.getMergeOperationStatus()) && mr.getMergeLeaseExpiresAt() != null
                && mr.getMergeLeaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_MERGE_IN_PROGRESS", "该 MR 正在合并中");
        }
        String operationId = mr.getMergeOperationId();
        if (operationId == null || operationId.isBlank()) {
            operationId = UuidV7.next().toString();
        }
        mr.setMergeOperationId(operationId);
        mr.setMergeOperationStatus("RUNNING");
        mr.setMergeLeaseExpiresAt(now.plus(MERGE_OPERATION_LEASE));
        mergeRequestMapper.updateById(mr);
        return new MergeClaim(mr, githubRepository, installation, operationId, false);
    }

    private MergeRequestEntity completeMerge(UUID projectId, UUID mergeRequestId, String operationId) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null || !operationId.equals(current.getMergeOperationId())) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_OPERATION_LOST", "合并操作租约已失效");
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(current.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        current.setStatus("MERGED");
        current.setMergeOperationStatus("COMPLETED");
        current.setMergeLeaseExpiresAt(null);
        current.setProviderUpdatedAt(now);
        current.setSyncedAt(now);
        mergeRequestMapper.updateById(current);
        return current;
    }

    private void failMerge(UUID mergeRequestId, String operationId) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null || !operationId.equals(current.getMergeOperationId())
                || "COMPLETED".equals(current.getMergeOperationStatus())) {
            return;
        }
        current.setMergeOperationStatus("FAILED");
        current.setMergeLeaseExpiresAt(null);
        mergeRequestMapper.updateById(current);
    }

    // ---------- 私有辅助 ----------

    private MergeRequestEntity persistRemoteState(UUID projectId, UUID mergeRequestId,
                                                  GitHubPullRequestDetails remote) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(current.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        current.setProviderNumber((long) remote.number());
        current.setSourceBranch(remote.headBranch());
        current.setTargetBranch(remote.baseBranch());
        current.setHeadCommit(remote.headSha());
        if (remote.title() != null) current.setTitle(remote.title());
        // 较早发起的同步请求不得把已经落库的 MERGED 终态覆盖回 OPEN。
        if (!"MERGED".equals(current.getStatus()) || remote.merged()) {
            current.setStatus(toLocalStatus(remote));
        }
        current.setProviderUpdatedAt(now);
        current.setSyncedAt(now);
        mergeRequestMapper.updateById(current);
        refreshQualityGate(current);
        return current;
    }

    /**
     * 加载 MR 并校验其仓库属于路径项目，禁止仅凭 UUID 跨项目查询。
     */
    private MergeRequestEntity requireMr(UUID projectId, UUID mergeRequestId) {
        MergeRequestEntity mr = mergeRequestMapper.selectById(mergeRequestId);
        if (mr == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        ProjectRepositoryEntity repo = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        return mr;
    }

    /**
     * CQ reviewer must differ from the MR author.
     */
    private void requireCqReviewer(MergeRequestEntity mr, UUID userId) {
        if (userId.equals(mr.getAuthorUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CQ_REVIEWER_NOT_ALLOWED", "MR 作者不能审查自己的 CQ");
        }
    }

    /**
     * 写入质量门禁检查结果（attemptNo 在同提交同类型内递增）。
     */
    private void writeCheck(MergeRequestEntity mr, String checkType, String status, String source, String reason,
                            LocalDateTime now) {
        QualityCheckResultEntity check = new QualityCheckResultEntity();
        check.setId(UuidV7.next());
        check.setMergeRequestId(mr.getId());
        check.setCheckType(checkType);
        check.setAttemptNo(nextAttemptNo(mr.getId(), checkType, mr.getHeadCommit()));
        check.setStatus(status);
        check.setCommitSha(mr.getHeadCommit());
        check.setSource(source);
        check.setSummary(reason == null ? Map.of() : Map.of("reason", reason));
        check.setStartedAt(now);
        check.setCompletedAt(now);
        check.setCreatedAt(now);
        qualityCheckMapper.insert(check);
    }

    /**
     * 汇总目标分支质量门禁：必检项全部 PASSED → PASSED；任一 FAILED → FAILED；缺失/运行中 → PENDING。
     */
    private QualityGateResponse qualityGate(MergeRequestEntity mr) {
        return qualityGates(List.of(mr)).get(mr.getId());
    }

    private String computeGateStatus(MergeRequestEntity mr, List<String> checks, List<UUID> requiredTestsets) {
        boolean anyFailed = false;
        int satisfied = 0;
        int total = requiredTestsets.size() + (int) checks.stream().filter(c -> !"TESTSET".equals(c)).count();
        if (total == 0) {
            return "PASSED";
        }
        for (UUID testsetId : requiredTestsets) {
            QualityCheckResultEntity r = latestCheck(mr.getId(), "TESTSET", mr.getHeadCommit(), testsetId);
            if (r == null) {
                continue;
            }
            if ("FAILED".equals(r.getStatus())) {
                anyFailed = true;
            } else if ("PASSED".equals(r.getStatus())) {
                satisfied++;
            }
        }
        for (String check : checks) {
            if ("TESTSET".equals(check)) {
                continue;
            }
            QualityCheckResultEntity r = latestCheck(mr.getId(), check, mr.getHeadCommit(), null);
            if (r == null) {
                continue;
            }
            if ("FAILED".equals(r.getStatus())) {
                anyFailed = true;
            } else if ("PASSED".equals(r.getStatus())) {
                satisfied++;
            }
        }
        if (anyFailed) {
            return "FAILED";
        }
        return satisfied >= total ? "PASSED" : "PENDING";
    }

    /**
     * 批量汇总多个 MR 的目标分支质量门禁状态，消除 N+1 查询
     */
    private Map<UUID, QualityGateResponse> qualityGates(List<MergeRequestEntity> mrs) {
        if (mrs.isEmpty()) {
            return Map.of();
        }

        List<UUID> repoIds = mrs.stream().map(MergeRequestEntity::getProjectRepositoryId).distinct().toList();
        List<RepositoryBranchConfigEntity> allConfigs = branchConfigMapper.selectList(
                Wrappers.<RepositoryBranchConfigEntity>query()
                        .in("project_repository_id", repoIds));

        Map<UUID, RepositoryBranchConfigEntity> mrConfigMap = new HashMap<>();
        for (MergeRequestEntity mr : mrs) {
            allConfigs.stream()
                    .filter(c -> java.util.Objects.equals(c.getProjectRepositoryId(), mr.getProjectRepositoryId()) && java.util.Objects.equals(c.getBranchName(), mr.getTargetBranch()))
                    .findFirst()
                    .ifPresent(c -> mrConfigMap.put(mr.getId(), c));
        }

        List<UUID> configIds = mrConfigMap.values().stream().map(RepositoryBranchConfigEntity::getId).distinct().toList();
        List<RepositoryBranchConfigTestsetEntity> allTestsets = configIds.isEmpty() ? List.of() :
                branchConfigTestsetMapper.selectList(Wrappers.<RepositoryBranchConfigTestsetEntity>query()
                        .in("branch_config_id", configIds));

        Map<UUID, List<UUID>> configTestsetsMap = allTestsets.stream()
                .collect(Collectors.groupingBy(RepositoryBranchConfigTestsetEntity::getBranchConfigId,
                        Collectors.mapping(RepositoryBranchConfigTestsetEntity::getTestsetId, Collectors.toList())));

        List<UUID> mrIds = mrs.stream().map(MergeRequestEntity::getId).toList();
        List<QualityCheckResultEntity> allChecks = qualityCheckMapper.selectList(
                Wrappers.<QualityCheckResultEntity>query()
                        .in("merge_request_id", mrIds)
                        .orderByDesc("attempt_no"));

        Map<UUID, QualityGateResponse> resultMap = new HashMap<>();
        for (MergeRequestEntity mr : mrs) {
            RepositoryBranchConfigEntity config = mrConfigMap.get(mr.getId());
            List<String> required = new ArrayList<>();
            if (config != null && config.getRequiredChecks() != null) {
                required.addAll(config.getRequiredChecks());
            }
            List<UUID> requiredTestsets = config == null ? List.of() :
                    configTestsetsMap.getOrDefault(config.getId(), List.of());

            if (!requiredTestsets.isEmpty() && !required.contains("TESTSET")) {
                required.add("TESTSET");
            }
            List<String> checks = required.stream().distinct().toList();

            List<QualityCheckResultEntity> mrChecks = allChecks.stream()
                    .filter(c -> java.util.Objects.equals(c.getMergeRequestId(), mr.getId()) && java.util.Objects.equals(c.getCommitSha(), mr.getHeadCommit()))
                    .toList();

            String status = computeGateStatusFromList(mrChecks, checks, requiredTestsets);
            resultMap.put(mr.getId(), new QualityGateResponse(status, checks));
        }
        return resultMap;
    }

    private String computeGateStatusFromList(List<QualityCheckResultEntity> mrChecks, List<String> checks, List<UUID> requiredTestsets) {
        boolean anyFailed = false;
        int satisfied = 0;
        int total = requiredTestsets.size() + (int) checks.stream().filter(c -> !"TESTSET".equals(c)).count();
        if (total == 0) {
            return "PASSED";
        }
        for (UUID testsetId : requiredTestsets) {
            QualityCheckResultEntity r = mrChecks.stream()
                    .filter(c -> "TESTSET".equals(c.getCheckType()) && testsetId.equals(c.getTestsetId()))
                    .findFirst().orElse(null);
            if (r == null) continue;
            if ("FAILED".equals(r.getStatus())) anyFailed = true;
            else if ("PASSED".equals(r.getStatus())) satisfied++;
        }
        for (String check : checks) {
            if ("TESTSET".equals(check)) continue;
            QualityCheckResultEntity r = mrChecks.stream()
                    .filter(c -> check.equals(c.getCheckType()) && c.getTestsetId() == null)
                    .findFirst().orElse(null);
            if (r == null) continue;
            if ("FAILED".equals(r.getStatus())) anyFailed = true;
            else if ("PASSED".equals(r.getStatus())) satisfied++;
        }
        if (anyFailed) return "FAILED";
        return satisfied >= total ? "PASSED" : "PENDING";
    }

    /**
     * 取 (mrId, checkType, commitSha[, testsetId]) 的最新 attempt_no 检查结果。
     */
    private QualityCheckResultEntity latestCheck(UUID mrId, String checkType, String commitSha, UUID testsetId) {
        return qualityCheckMapper.selectOne(Wrappers.<QualityCheckResultEntity>lambdaQuery()
                .eq(QualityCheckResultEntity::getMergeRequestId, mrId)
                .eq(QualityCheckResultEntity::getCheckType, checkType)
                .eq(QualityCheckResultEntity::getCommitSha, commitSha)
                .eq(testsetId != null, QualityCheckResultEntity::getTestsetId, testsetId)
                .orderByDesc(QualityCheckResultEntity::getAttemptNo)
                .last("LIMIT 1"));
    }

    private int nextAttemptNo(UUID mrId, String checkType, String commitSha) {
        QualityCheckResultEntity last = latestCheck(mrId, checkType, commitSha, null);
        return (last == null || last.getAttemptNo() == null) ? 1 : last.getAttemptNo() + 1;
    }

    /**
     * 重算并持久化 MR 的门禁汇总状态。
     */
    private void refreshQualityGate(MergeRequestEntity mr) {
        String status = qualityGate(mr).getStatus();
        if (!status.equals(mr.getQualityGateStatus())) {
            mr.setQualityGateStatus(status);
            mergeRequestMapper.updateById(mr);
        }
    }

    private GitHubRepositoryEntity requireGitHubRepository(UUID projectId, UUID projectRepositoryId) {
        ProjectRepositoryEntity binding = projectRepositoryMapper.selectById(projectRepositoryId);
        if (binding == null || !projectId.equals(binding.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository does not exist or is not visible");
        }
        GitHubRepositoryEntity repository = githubRepositoryMapper.selectById(binding.getRepositoryId());
        if (repository == null || Boolean.TRUE.equals(repository.getArchived())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_UNAVAILABLE",
                    "The bound GitHub repository is unavailable or archived");
        }
        // 已撤权（REVOKED）的仓库镜像不可再用于创建/同步/合并 PR，调用 GitHub 前统一拒绝
        if (!"AUTHORIZED".equals(repository.getAuthorizationStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_UNAVAILABLE",
                    "The bound GitHub repository authorization has been revoked");
        }
        var project = projectMapper.selectById(projectId);
        GitHubInstallationEntity installation = repository.getInstallationId() == null ? null
                : githubInstallationMapper.selectById(repository.getInstallationId());
        if (project == null || installation == null || !project.getTeamId().equals(installation.getTeamId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_NOT_AUTHORIZED",
                    "The GitHub repository installation is not authorized for this project");
        }
        return repository;
    }

    private GitHubInstallationEntity requireInstallation(GitHubRepositoryEntity repository) {
        GitHubInstallationEntity installation = githubInstallationMapper.selectById(repository.getInstallationId());
        if (installation == null || !"ACTIVE".equalsIgnoreCase(installation.getStatus())
                || installation.getProviderInstallationId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INSTALLATION_UNAVAILABLE",
                    "No active GitHub App installation is available for this repository");
        }
        return installation;
    }

    private int requireProviderNumber(MergeRequestEntity mr) {
        if (mr.getProviderNumber() == null || mr.getProviderNumber() <= 0 || mr.getProviderNumber() > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_PR_NUMBER_MISSING", "The MR has no valid GitHub Pull Request number");
        }
        return mr.getProviderNumber().intValue();
    }

    private String toLocalStatus(GitHubPullRequestDetails remote) {
        if (remote.merged()) {
            return "MERGED";
        }
        return "open".equalsIgnoreCase(remote.state()) ? "OPEN" : "CLOSED";
    }

    /**
     * 批量取 MR 的需求群ID映射，避免列表 N+1 查询。
     */
    private Map<UUID, List<String>> groupIdsByMr(List<MergeRequestEntity> mrs) {
        if (mrs.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = mrs.stream().map(MergeRequestEntity::getId).toList();
        return mergeRequestGroupMapper.selectByMergeRequestIds(ids).stream().collect(Collectors.groupingBy(
                MergeRequestGroupEntity::getMergeRequestId,
                Collectors.mapping(r -> id(r.getRequirementGroupId()), Collectors.toList())));
    }

    private void publishUpdated(MergeRequestEntity mr) {
        ProjectRepositoryEntity repo = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        Map<String, Object> p = new HashMap<>();
        if (repo != null) {
            p.put("projectId", repo.getProjectId());
        }
        p.put("repositoryId", mr.getProjectRepositoryId());
        p.put("mergeRequestId", mr.getId());
        p.put("number", mr.getProviderNumber());
        p.put("status", mr.getStatus());
        p.put("headCommit", mr.getHeadCommit());
        p.put("providerUpdatedAt", iso(mr.getProviderUpdatedAt()));
        p.put("qualityGateStatus", mr.getQualityGateStatus());
        p.put("timestamp", Instant.now().toString());
        eventService.publish(repo == null ? null : repo.getProjectId(), null, "merge-request.updated",
                mr.getId().toString(), p);
        notifyMrPending(mr, repo == null ? null : repo.getProjectId());
    }

    /**
     * MR 状态更新后向任务发起人写入通知（A 联调约定 §1）。
     * MR 未关联任务或发起人缺失时静默跳过，不阻断 MR 同步。
     */
    private void notifyMrPending(MergeRequestEntity mr, UUID projectId) {
        if (mr.getTaskId() == null) {
            return;
        }
        TaskEntity task = taskMapper.selectById(mr.getTaskId());
        if (task == null) {
            return;
        }
        notificationService.notify(task.getCreatedBy(), projectId, task.getRequirementGroupId(), "MR_PENDING",
                "MR 状态更新：" + (mr.getTitle() == null || mr.getTitle().isBlank() ? mr.getProviderNumber() : mr.getTitle()),
                mr.getStatus(), mr.getId().toString());
    }

    /**
     * 构造 MR 的 GitHub Web 地址（repo 或 providerNumber 缺失时返回 null，不抛错）。
     */
    private String mrWebUrl(MergeRequestEntity mr) {
        ProjectRepositoryEntity binding = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        if (binding == null || mr.getProviderNumber() == null) {
            return null;
        }
        GitHubRepositoryEntity repo = githubRepositoryMapper.selectById(binding.getRepositoryId());
        if (repo == null || repo.getOwnerLogin() == null || repo.getName() == null) {
            return null;
        }
        return "https://github.com/" + repo.getOwnerLogin() + "/" + repo.getName() + "/pull/"
                + mr.getProviderNumber();
    }

    /**
     * 批量构造 MR 的 GitHub Web 地址（列表页用，避免逐条查询）。
     */
    private Map<UUID, String> webUrlsByMr(List<MergeRequestEntity> rows) {
        Set<UUID> bindingIds = rows.stream().map(MergeRequestEntity::getProjectRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (bindingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProjectRepositoryEntity> bindingById = projectRepositoryMapper
                .selectBatchIds(bindingIds).stream()
                .collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity()));
        Set<UUID> githubIds = bindingById.values().stream().map(ProjectRepositoryEntity::getRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, GitHubRepositoryEntity> githubById = githubIds.isEmpty() ? Collections.emptyMap()
                : githubRepositoryMapper.selectBatchIds(githubIds).stream()
                .collect(Collectors.toMap(GitHubRepositoryEntity::getId, Function.identity()));
        Map<UUID, String> result = new HashMap<>();
        for (MergeRequestEntity mr : rows) {
            ProjectRepositoryEntity binding = bindingById.get(mr.getProjectRepositoryId());
            GitHubRepositoryEntity repo = binding == null ? null : githubById.get(binding.getRepositoryId());
            if (repo == null || mr.getProviderNumber() == null) {
                result.put(mr.getId(), null);
            } else {
                result.put(mr.getId(), "https://github.com/" + repo.getOwnerLogin() + "/" + repo.getName()
                        + "/pull/" + mr.getProviderNumber());
            }
        }
        return result;
    }

    /**
     * 查询 MR 关联的已接受 Diff ID（同任务、同仓库、status=ACCEPTED，取最新）；无则 null。
     * 供 MR 详情「变更 / 评论」Tab 直接定位 Diff。
     */
    private String acceptedDiffId(MergeRequestEntity mr) {
        if (mr.getTaskId() == null) {
            return null;
        }
        DiffEntity diff = diffMapper.selectOne(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getTaskId, mr.getTaskId())
                .eq(DiffEntity::getProjectRepositoryId, mr.getProjectRepositoryId())
                .eq(DiffEntity::getStatus, "ACCEPTED")
                .orderByDesc(DiffEntity::getCreatedAt).last("LIMIT 1"));
        return diff == null ? null : id(diff.getId());
    }

    private MergeRequestSummaryResponse toSummary(MergeRequestEntity mr, List<String> groupIds,
                                                  QualityGateResponse gate, String webUrl) {
        return new MergeRequestSummaryResponse(id(mr.getId()), id(mr.getProjectRepositoryId()), groupIds,
                mr.getProvider(), mr.getProviderNumber(), mr.getSourceBranch(), mr.getTargetBranch(), mr.getStatus(),
                mr.getHeadCommit(), gate, mr.getTitle(), webUrl, iso(mr.getCreatedAt()));
    }

    private MergeRequestDetailResponse toDetail(MergeRequestEntity mr, List<String> groupIds,
                                                QualityGateResponse gate, String webUrl, String diffId) {
        return new MergeRequestDetailResponse(id(mr.getId()), id(mr.getProjectRepositoryId()), groupIds,
                mr.getProvider(), mr.getProviderNumber(), mr.getSourceBranch(), mr.getTargetBranch(), mr.getStatus(),
                mr.getHeadCommit(), mr.getTitle(), null, webUrl, diffId, gate, id(mr.getAuthorUserId()),
                iso(mr.getSyncedAt()), iso(mr.getCreatedAt()));
    }

    private MergeRequestCheckResponse toCheck(QualityCheckResultEntity c) {
        return new MergeRequestCheckResponse(id(c.getId()), c.getCheckType(), c.getStatus(), c.getAttemptNo(),
                id(c.getTestsetId()), c.getCommitSha(), c.getSource(), c.getSummary(), iso(c.getStartedAt()),
                iso(c.getCompletedAt()));
    }

    private MergeRequestReviewResponse toReview(MergeRequestReviewEntity r) {
        return new MergeRequestReviewResponse(id(r.getId()), r.getReviewKind(), id(r.getReviewerUserId()),
                r.getReviewerName(), r.getDecision(), r.getSummary(), iso(r.getReviewedAt()));
    }

    private ApiPageResponse<MergeRequestSummaryResponse> emptyPage(String requestId) {
        return new ApiPageResponse<>(List.of(), new PageMeta(null, false), requestId);
    }

    private UUID parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
