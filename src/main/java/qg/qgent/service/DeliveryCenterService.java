package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 交付中心聚合服务（契约 v1.8.0 §20，成员 B B01/B02）。
 * <p>
 * 把 CODE（Task 级 DiffReviewBatch + Diff + MR）、MEMORY、SKILL 三类项目资源聚合为
 * 统一的交付项列表与统计。所有派生字段（displayStatus/capabilities/openTarget）由后端
 * 按真实资源状态与当前用户角色计算；聚合列表只返回脱敏摘要，不包含完整内容、
 * Prompt、Token、凭据或代码 Patch。跨类型合并排序后按 updatedAt 倒序，cursor 为
 * 偏移量的 base64 编码（实现细节，前端只需回传 nextCursor）。
 */
@Service
public class DeliveryCenterService {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final int EXCERPT_LIMIT = 200;
    private static final Set<String> DELIVERY_TYPES = Set.of("CODE", "MEMORY", "SKILL");

    private final DiffReviewBatchMapper diffBatches;
    private final DiffMapper diffs;
    private final MergeRequestMapper mergeRequests;
    private final TaskMapper tasks;
    private final RequirementGroupMapper groups;
    private final ProjectRepositoryMapper projectRepositories;
    private final WorkspaceRepositoryMapper worktrees;
    private final GitHubRepositoryMapper githubRepositories;
    private final MemoryMapper memories;
    private final SkillMapper skills;
    private final UserMapper users;
    private final ProjectAccessService access;

    public DeliveryCenterService(DiffReviewBatchMapper diffBatches, DiffMapper diffs, MergeRequestMapper mergeRequests,
                                 TaskMapper tasks, RequirementGroupMapper groups, ProjectRepositoryMapper projectRepositories,
                                 WorkspaceRepositoryMapper worktrees, GitHubRepositoryMapper githubRepositories,
                                 MemoryMapper memories, SkillMapper skills, UserMapper users, ProjectAccessService access) {
        this.diffBatches = diffBatches;
        this.diffs = diffs;
        this.mergeRequests = mergeRequests;
        this.tasks = tasks;
        this.groups = groups;
        this.projectRepositories = projectRepositories;
        this.worktrees = worktrees;
        this.githubRepositories = githubRepositories;
        this.memories = memories;
        this.skills = skills;
        this.users = users;
        this.access = access;
    }

    /**
     * 交付中心聚合列表：按筛选条件加载三类资源、合并后按更新时间倒序，游标分页。
     *
     * @param projectId    项目 ID
     * @param actor        当前用户 ID
     * @param groupId      需求群筛选（可选）
     * @param type         资源类型筛选：CODE/MEMORY/SKILL（可选）
     * @param displayStatus 展示状态筛选（可选）
     * @param repositoryId 项目仓库绑定 ID 筛选（仅 CODE 匹配）
     * @param createdBy    创建者筛选（可选）
     * @param cursor       分页游标（上一页 nextCursor）
     * @param limit        每页条数（默认 30，最大 100）
     * @param requestId    请求 ID
     * @return 统一 cursor envelope 的交付项列表
     */
    public PagedApiResponse<DeliveryItem> list(UUID projectId, UUID actor, String groupId, String type,
                                               String displayStatus, String repositoryId, String createdBy,
                                               String cursor, Integer limit, String requestId) {
        access.requireProjectMember(projectId, actor);
        UUID groupUuid = optionalUuid(groupId, "INVALID_GROUP_FILTER");
        UUID repositoryUuid = optionalUuid(repositoryId, "INVALID_REPOSITORY_FILTER");
        UUID creatorUuid = optionalUuid(createdBy, "INVALID_CREATEDBY_FILTER");
        List<DeliveryItem> all = collect(projectId, actor, groupUuid, type, displayStatus, repositoryUuid, creatorUuid);

        int size = clampLimit(limit);
        int offset = decodeCursor(cursor);
        boolean hasMore = offset + size < all.size();
        List<DeliveryItem> page = all.stream().skip(offset).limit(size).toList();
        String next = hasMore ? encodeCursor(offset + size) : null;
        return new PagedApiResponse<>(page, new PageInfo(next, hasMore), requestId);
    }

    /**
     * 交付中心聚合统计：针对完整筛选数据集计算，不由当前分页推导。
     */
    public DeliverySummaryResponse summary(UUID projectId, UUID actor, String groupId, String repositoryId) {
        access.requireProjectMember(projectId, actor);
        UUID groupUuid = optionalUuid(groupId, "INVALID_GROUP_FILTER");
        UUID repositoryUuid = optionalUuid(repositoryId, "INVALID_REPOSITORY_FILTER");
        List<DeliveryItem> all = collect(projectId, actor, groupUuid, null, null, repositoryUuid, null);

        long code = 0, memory = 0, skill = 0;
        long draft = 0, pendingReview = 0, processing = 0, accepted = 0, rejected = 0, delivered = 0,
                failed = 0, archived = 0;
        long pendingForCurrentUser = 0;
        for (DeliveryItem item : all) {
            switch (item.getResourceType()) {
                case "CODE" -> code++;
                case "MEMORY" -> memory++;
                default -> skill++;
            }
            switch (item.getDisplayStatus()) {
                case "DRAFT" -> draft++;
                case "PENDING_REVIEW" -> pendingReview++;
                case "PROCESSING" -> processing++;
                case "ACCEPTED" -> accepted++;
                case "REJECTED" -> rejected++;
                case "DELIVERED" -> delivered++;
                case "FAILED" -> failed++;
                case "ARCHIVED" -> archived++;
                default -> { }
            }
            if (isPendingForCurrentUser(item)) {
                pendingForCurrentUser++;
            }
        }
        return new DeliverySummaryResponse(all.size(),
                new DeliverySummaryResponse.TypeCounts(code, memory, skill),
                new DeliverySummaryResponse.StatusCounts(draft, pendingReview, processing, accepted, rejected,
                        delivered, failed, archived),
                pendingForCurrentUser,
                repositorySummaries(all),
                groupSummaries(all),
                iso(LocalDateTime.now(ZoneOffset.UTC)));
    }

    // ---------- 聚合加载 ----------

    /**
     * 加载三类资源并组装为统一交付项列表（按 updatedAt 倒序）。
     */
    private List<DeliveryItem> collect(UUID projectId, UUID actor, UUID groupUuid, String type,
                                       String displayStatus, UUID repositoryUuid, UUID creatorUuid) {
        List<DeliveryItem> items = new ArrayList<>();
        if (type == null || "CODE".equals(type)) {
            items.addAll(codeItems(projectId, actor, groupUuid, displayStatus, repositoryUuid, creatorUuid));
        }
        if (type == null || "MEMORY".equals(type)) {
            items.addAll(memoryItems(projectId, actor, groupUuid, displayStatus, creatorUuid));
        }
        if (type == null || "SKILL".equals(type)) {
            items.addAll(skillItems(projectId, actor, groupUuid, displayStatus, creatorUuid));
        }
        return items.stream()
                .sorted(Comparator.comparing(DeliveryItem::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    private List<DeliveryItem> codeItems(UUID projectId, UUID actor, UUID groupUuid, String displayStatus,
                                         UUID repositoryUuid, UUID creatorUuid) {
        List<DiffReviewBatchEntity> batches = diffBatches.selectList(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getProjectId, projectId));
        if (batches.isEmpty()) {
            return List.of();
        }
        List<UUID> batchIds = batches.stream().map(DiffReviewBatchEntity::getId).toList();
        List<DiffEntity> allDiffs = diffs.selectList(Wrappers.<DiffEntity>lambdaQuery()
                .in(DiffEntity::getReviewBatchId, batchIds));
        Map<UUID, List<DiffEntity>> diffsByBatch = allDiffs.stream()
                .collect(Collectors.groupingBy(DiffEntity::getReviewBatchId));
        Set<UUID> taskIds = batches.stream().map(DiffReviewBatchEntity::getTaskId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, TaskEntity> taskById = taskIds.isEmpty() ? Collections.emptyMap() : tasks
                .selectList(Wrappers.<TaskEntity>lambdaQuery().in(TaskEntity::getId, taskIds)).stream()
                .collect(Collectors.toMap(TaskEntity::getId, Function.identity()));
        Set<UUID> groupIds = taskById.values().stream().map(TaskEntity::getRequirementGroupId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, RequirementGroupEntity> groupById = groupIds.isEmpty() ? Collections.emptyMap() : groups
                .selectList(Wrappers.<RequirementGroupEntity>lambdaQuery().in(RequirementGroupEntity::getId, groupIds))
                .stream().collect(Collectors.toMap(RequirementGroupEntity::getId, Function.identity()));
        Set<UUID> repoIds = allDiffs.stream().map(DiffEntity::getProjectRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, ProjectRepositoryEntity> bindingById = repoIds.isEmpty() ? Collections.emptyMap()
                : projectRepositories
                .selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery().in(ProjectRepositoryEntity::getId, repoIds))
                .stream().collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity()));
        Map<UUID, GitHubRepositoryEntity> githubById = loadGithub(bindingById.values());
        Set<UUID> workspaceIds = batches.stream().map(DiffReviewBatchEntity::getWorkspaceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, WorkspaceRepositoryEntity> worktreeByRepo = workspaceIds.isEmpty() ? Collections.emptyMap()
                : worktrees.selectByWorkspaces(new ArrayList<>(workspaceIds)).stream()
                .collect(Collectors.toMap(WorkspaceRepositoryEntity::getProjectRepositoryId,
                        Function.identity(), (a, b) -> a));
        List<MergeRequestEntity> mrs = taskIds.isEmpty() ? List.of() : mergeRequests
                .selectList(Wrappers.<MergeRequestEntity>lambdaQuery().in(MergeRequestEntity::getTaskId, taskIds));
        Map<UUID, List<MergeRequestEntity>> mrByTask = mrs.stream()
                .collect(Collectors.groupingBy(MergeRequestEntity::getTaskId));
        Map<String, MergeRequestEntity> mrByRepoTask = mrs.stream()
                .collect(Collectors.toMap(m -> m.getProjectRepositoryId() + ":" + m.getTaskId(), Function.identity(),
                        (a, b) -> b));

        List<DeliveryItem> result = new ArrayList<>();
        for (DiffReviewBatchEntity batch : batches) {
            TaskEntity task = taskById.get(batch.getTaskId());
            if (task == null) {
                continue;
            }
            if (groupUuid != null && !groupUuid.equals(task.getRequirementGroupId())) {
                continue;
            }
            if (creatorUuid != null && !creatorUuid.equals(task.getCreatedBy())) {
                continue;
            }
            List<DiffEntity> batchDiffs = diffsByBatch.getOrDefault(batch.getId(), List.of());
            if (repositoryUuid != null && batchDiffs.stream()
                    .noneMatch(d -> repositoryUuid.equals(d.getProjectRepositoryId()))) {
                continue;
            }
            CodeDeliveryItem item = toCodeItem(batch, task, groupById.get(task.getRequirementGroupId()),
                    batchDiffs, bindingById, githubById, worktreeByRepo, mrByTask.getOrDefault(batch.getTaskId(), List.of()),
                    mrByRepoTask, actor);
            if (displayStatus != null && !displayStatus.equals(item.getDisplayStatus())) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    private List<DeliveryItem> memoryItems(UUID projectId, UUID actor, UUID groupUuid, String displayStatus,
                                           UUID creatorUuid) {
        if (groupUuid != null) {
            // Memory 无需求群来源，按群筛选时不返回 MEMORY 项
            return List.of();
        }
        return memories.selectList(Wrappers.<MemoryEntity>lambdaQuery()
                        .eq(MemoryEntity::getProjectId, projectId))
                .stream()
                .filter(m -> creatorUuid == null || creatorUuid.equals(m.getCreatedBy()))
                .map(m -> toMemoryItem(projectId, m, actor))
                .filter(i -> displayStatus == null || displayStatus.equals(i.getDisplayStatus()))
                .map(i -> (DeliveryItem) i)
                .toList();
    }

    private List<DeliveryItem> skillItems(UUID projectId, UUID actor, UUID groupUuid, String displayStatus,
                                          UUID creatorUuid) {
        if (groupUuid != null) {
            return List.of();
        }
        return skills.selectList(Wrappers.<SkillEntity>lambdaQuery()
                        .eq(SkillEntity::getProjectId, projectId))
                .stream()
                .filter(s -> creatorUuid == null || creatorUuid.equals(s.getCreatedBy()))
                .map(s -> toSkillItem(projectId, s, actor))
                .filter(i -> displayStatus == null || displayStatus.equals(i.getDisplayStatus()))
                .map(i -> (DeliveryItem) i)
                .toList();
    }

    // ---------- 单类组装 ----------

    private CodeDeliveryItem toCodeItem(DiffReviewBatchEntity batch, TaskEntity task,
                                        RequirementGroupEntity group, List<DiffEntity> batchDiffs,
                                        Map<UUID, ProjectRepositoryEntity> bindingById,
                                        Map<UUID, GitHubRepositoryEntity> githubById,
                                        Map<UUID, WorkspaceRepositoryEntity> worktreeByRepo,
                                        List<MergeRequestEntity> taskMrs,
                                        Map<String, MergeRequestEntity> mrByRepoTask, UUID actor) {
        CodeDeliveryItem item = new CodeDeliveryItem();
        String batchId = id(batch.getId());
        item.setId(batchId);
        item.setProjectId(id(task.getProjectId()));
        item.setResourceType("CODE");
        item.setResourceId(batchId);
        item.setTitle(task.getTitle());
        item.setSummary(null);
        item.setVersion(null);
        item.setResourceStatus(batch.getReviewStatus());
        item.setRequirementGroup(groupRef(group));
        item.setSource(source(task, batch));
        item.setCreator(userSummary(task.getCreatedBy()));
        item.setSubmitter(null);
        item.setReviewer(userSummary(batch.getReviewedBy()));
        item.setReviewReason(batch.getReviewReason());
        item.setCreatedAt(iso(batch.getCreatedAt()));
        item.setSubmittedAt(null);
        item.setReviewedAt(iso(batch.getReviewedAt()));
        item.setUpdatedAt(iso(batch.getUpdatedAt()));

        item.setDiffReviewId(batchId);
        item.setReviewStatus(batch.getReviewStatus());
        item.setDeliveryStatus(batch.getDeliveryStatus());
        item.setDisplayStatus(codeDisplayStatus(batch));
        item.setOpenTarget(DeliveryOpenTarget.taskDiffReview(id(task.getId()), batchId));

        List<DiffEntity> ordered = batchDiffs.stream()
                .sorted(Comparator.comparing(DiffEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int files = 0, additions = 0, deletions = 0;
        for (DiffEntity diff : ordered) {
            Map<String, Object> stats = diff.getChangeStats();
            if (stats != null) {
                files += intValue(stats.get("files"));
                additions += intValue(stats.get("additions"));
                deletions += intValue(stats.get("deletions"));
            }
        }
        item.setFilesChanged(files);
        item.setAdditions(additions);
        item.setDeletions(deletions);

        List<CodeDeliveryItem.RepositoryRef> repos = new ArrayList<>();
        for (DiffEntity diff : ordered) {
            ProjectRepositoryEntity binding = bindingById.get(diff.getProjectRepositoryId());
            String name = bindingName(binding, githubById.get(binding == null ? null : binding.getRepositoryId()));
            WorkspaceRepositoryEntity worktree = worktreeByRepo.get(diff.getProjectRepositoryId());
            repos.add(new CodeDeliveryItem.RepositoryRef(id(diff.getProjectRepositoryId()), name,
                    worktree == null ? null : worktree.getSourceBranch()));
        }
        item.setRepositories(repos);

        List<RepositoryDeliverySummary> deliveries = new ArrayList<>();
        for (DiffEntity diff : ordered) {
            ProjectRepositoryEntity binding = bindingById.get(diff.getProjectRepositoryId());
            MergeRequestEntity mr = mrByRepoTask.get(id(diff.getProjectRepositoryId()) + ":" + id(task.getId()));
            deliveries.add(new RepositoryDeliverySummary(id(diff.getProjectRepositoryId()),
                    bindingName(binding, githubById.get(binding == null ? null : binding.getRepositoryId())),
                    diff.getDeliveryStatus(), diff.getDeliveryFailureCode(), diff.getDeliveryFailureReason(),
                    mr == null ? null : mergeRequestSummary(mr, binding, githubById), iso(diff.getUpdatedAt())));
        }
        item.setRepositoryDeliveries(deliveries);

        MergeRequestEntity firstMr = taskMrs.stream()
                .sorted(Comparator.comparing(MergeRequestEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst().orElse(null);
        if (firstMr != null) {
            ProjectRepositoryEntity binding = bindingById.get(firstMr.getProjectRepositoryId());
            item.setMergeRequest(mergeRequestSummary(firstMr, binding, githubById));
        } else {
            item.setMergeRequest(null);
        }
        item.setCapabilities(codeCapabilities(batch, task, actor));
        return item;
    }

    private MemoryDeliveryItem toMemoryItem(UUID projectId, MemoryEntity memory, UUID actor) {
        MemoryDeliveryItem item = new MemoryDeliveryItem();
        String memoryId = id(memory.getId());
        item.setId(memoryId);
        item.setProjectId(id(projectId));
        item.setResourceType("MEMORY");
        item.setResourceId(memoryId);
        item.setTitle(memory.getTitle());
        item.setSummary(excerpt(memory.getContent()));
        item.setVersion(null);
        item.setResourceStatus(memory.getStatus());
        item.setDisplayStatus(memoryDisplayStatus(memory.getStatus()));
        item.setRequirementGroup(null);
        item.setSource(new DeliveryItem.DeliverySource(null, null, null, null, null, null, null));
        item.setCreator(userSummary(memory.getCreatedBy()));
        item.setSubmitter(null);
        item.setReviewer(userSummary(memory.getReviewerId()));
        item.setReviewReason(memory.getRejectionReason());
        item.setCreatedAt(iso(memory.getCreatedAt()));
        item.setSubmittedAt(null);
        item.setReviewedAt(iso(memory.getReviewedAt()));
        item.setUpdatedAt(iso(memory.getUpdatedAt()));
        item.setCategory(memory.getCategory());
        item.setTags(memory.getTags() == null ? List.of() : memory.getTags());
        item.setVisibility("PROJECT_SHARED");
        item.setSources(List.of());
        item.setContentExcerpt(excerpt(memory.getContent()));
        item.setCapabilities(memoryCapabilities(memory, actor));
        item.setOpenTarget(DeliveryOpenTarget.memory(memoryId));
        return item;
    }

    private SkillDeliveryItem toSkillItem(UUID projectId, SkillEntity skill, UUID actor) {
        SkillDeliveryItem item = new SkillDeliveryItem();
        String skillId = id(skill.getId());
        item.setId(skillId);
        item.setProjectId(id(projectId));
        item.setResourceType("SKILL");
        item.setResourceId(skillId);
        item.setTitle(skill.getName());
        item.setSummary(excerpt(skill.getContent()));
        item.setVersion(null);
        item.setResourceStatus(skill.getStatus());
        item.setDisplayStatus(skillDisplayStatus(skill.getStatus()));
        item.setRequirementGroup(null);
        item.setSource(new DeliveryItem.DeliverySource(null, null, null, null, null, null, null));
        item.setCreator(userSummary(skill.getCreatedBy()));
        item.setSubmitter(null);
        item.setReviewer(userSummary(skill.getReviewerId()));
        item.setReviewReason(skill.getRejectionReason());
        item.setCreatedAt(iso(skill.getCreatedAt()));
        item.setSubmittedAt(null);
        item.setReviewedAt(iso(skill.getReviewedAt()));
        item.setUpdatedAt(iso(skill.getUpdatedAt()));
        item.setTags(skill.getTags() == null ? List.of() : skill.getTags());
        item.setVisibility(skill.getVisibility());
        item.setCapabilitySummary(null);
        item.setContentExcerpt(excerpt(skill.getContent()));
        item.setCapabilities(skillCapabilities(skill, actor));
        item.setOpenTarget(DeliveryOpenTarget.skill(skillId));
        return item;
    }

    // ---------- 状态 / 能力派生 ----------

    /**
     * CODE 展示状态映射：PENDING_CONFIRMATION→PROCESSING；ACCEPTED 按交付状态细分。
     */
    private String codeDisplayStatus(DiffReviewBatchEntity batch) {
        return switch (batch.getReviewStatus()) {
            case "PENDING_CONFIRMATION" -> "PROCESSING";
            case "REJECTED" -> "REJECTED";
            case "ACCEPTED" -> switch (batch.getDeliveryStatus() == null ? "" : batch.getDeliveryStatus()) {
                case "DELIVERED" -> "DELIVERED";
                case "PARTIALLY_DELIVERED", "FAILED" -> "FAILED";
                default -> "PROCESSING";
            };
            default -> "PROCESSING";
        };
    }

    private String memoryDisplayStatus(String status) {
        return "APPROVED".equals(status) ? "ACCEPTED" : status;
    }

    private String skillDisplayStatus(String status) {
        return "PUBLISHED".equals(status) ? "ACCEPTED" : status;
    }

    private DeliveryCapabilities codeCapabilities(DiffReviewBatchEntity batch, TaskEntity task, UUID actor) {
        boolean ownerOrAdmin = access.isOwnerOrAdmin(task.getCreatedBy(), task.getProjectId(), actor);
        boolean pendingConfirmation = "PENDING_CONFIRMATION".equals(batch.getReviewStatus());
        boolean accepted = "ACCEPTED".equals(batch.getReviewStatus());
        String deliveryStatus = batch.getDeliveryStatus() == null ? "" : batch.getDeliveryStatus();
        boolean retryable = accepted && ("PARTIALLY_DELIVERED".equals(deliveryStatus) || "FAILED".equals(deliveryStatus));
        String forbid = ownerOrAdmin ? null : "DIFF_REVIEW_FORBIDDEN";

        DeliveryCapabilities caps = new DeliveryCapabilities();
        caps.setCanSubmitReview(false);
        caps.setCanApprove(ownerOrAdmin && pendingConfirmation);
        caps.setCanReject(ownerOrAdmin && pendingConfirmation);
        caps.setCanArchive(false);
        caps.setCanRetryDelivery(ownerOrAdmin && retryable);
        caps.setCanOpenResource(true);
        DeliveryCapabilities.DeliveryCapabilityReasons reasons = new DeliveryCapabilities.DeliveryCapabilityReasons();
        reasons.setCanSubmitReview("NOT_SUPPORTED");
        reasons.setCanApprove(!pendingConfirmation ? "DIFF_REVIEW_NOT_DECIDABLE" : forbid);
        reasons.setCanReject(!pendingConfirmation ? "DIFF_REVIEW_NOT_DECIDABLE" : forbid);
        reasons.setCanArchive("NOT_SUPPORTED");
        reasons.setCanRetryDelivery(!retryable ? "DIFF_DELIVERY_NOT_RETRYABLE" : forbid);
        reasons.setCanOpenResource(null);
        caps.setDisabledReasons(reasons);
        return caps;
    }

    private DeliveryCapabilities memoryCapabilities(MemoryEntity memory, UUID actor) {
        boolean admin = access.isProjectAdmin(memory.getProjectId(), actor);
        boolean creatorOrAdmin = admin || memory.getCreatedBy().equals(actor);
        String status = memory.getStatus();
        boolean submittable = creatorOrAdmin && ("DRAFT".equals(status) || "REJECTED".equals(status));
        boolean decidable = admin && "PENDING_REVIEW".equals(status);
        boolean archivable = admin && "APPROVED".equals(status);

        DeliveryCapabilities caps = new DeliveryCapabilities();
        caps.setCanSubmitReview(submittable);
        caps.setCanApprove(decidable);
        caps.setCanReject(decidable);
        caps.setCanArchive(archivable);
        caps.setCanRetryDelivery(false);
        caps.setCanOpenResource(true);
        DeliveryCapabilities.DeliveryCapabilityReasons reasons = new DeliveryCapabilities.DeliveryCapabilityReasons();
        reasons.setCanSubmitReview(!submittable ? (!creatorOrAdmin ? "MEMORY_FORBIDDEN" : "MEMORY_STATE_CONFLICT") : null);
        reasons.setCanApprove(!decidable ? (!admin ? "PROJECT_ADMIN_REQUIRED" : "MEMORY_STATE_CONFLICT") : null);
        reasons.setCanReject(reasons.getCanApprove());
        reasons.setCanArchive(!archivable ? (!admin ? "PROJECT_ADMIN_REQUIRED" : "MEMORY_STATE_CONFLICT") : null);
        reasons.setCanRetryDelivery("NOT_SUPPORTED");
        reasons.setCanOpenResource(null);
        caps.setDisabledReasons(reasons);
        return caps;
    }

    private DeliveryCapabilities skillCapabilities(SkillEntity skill, UUID actor) {
        boolean admin = access.isProjectAdmin(skill.getProjectId(), actor);
        boolean creatorOrAdmin = admin || skill.getCreatedBy().equals(actor);
        String status = skill.getStatus();
        boolean submittable = creatorOrAdmin && ("DRAFT".equals(status) || "REJECTED".equals(status));
        boolean decidable = admin && "PENDING_REVIEW".equals(status);
        boolean archivable = admin && "PUBLISHED".equals(status);

        DeliveryCapabilities caps = new DeliveryCapabilities();
        caps.setCanSubmitReview(submittable);
        caps.setCanApprove(decidable);
        caps.setCanReject(decidable);
        caps.setCanArchive(archivable);
        caps.setCanRetryDelivery(false);
        caps.setCanOpenResource(true);
        DeliveryCapabilities.DeliveryCapabilityReasons reasons = new DeliveryCapabilities.DeliveryCapabilityReasons();
        reasons.setCanSubmitReview(!submittable ? (!creatorOrAdmin ? "SKILL_FORBIDDEN" : "SKILL_STATE_CONFLICT") : null);
        reasons.setCanApprove(!decidable ? (!admin ? "PROJECT_ADMIN_REQUIRED" : "SKILL_STATE_CONFLICT") : null);
        reasons.setCanReject(reasons.getCanApprove());
        reasons.setCanArchive(!archivable ? (!admin ? "PROJECT_ADMIN_REQUIRED" : "SKILL_STATE_CONFLICT") : null);
        reasons.setCanRetryDelivery("NOT_SUPPORTED");
        reasons.setCanOpenResource(null);
        caps.setDisabledReasons(reasons);
        return caps;
    }

    private boolean isPendingForCurrentUser(DeliveryItem item) {
        DeliveryCapabilities caps = item.getCapabilities();
        if (caps == null) {
            return false;
        }
        boolean actionable = caps.isCanSubmitReview() || caps.isCanApprove() || caps.isCanReject()
                || caps.isCanArchive() || caps.isCanRetryDelivery();
        if (!actionable) {
            return false;
        }
        return switch (item.getDisplayStatus() == null ? "" : item.getDisplayStatus()) {
            case "DRAFT", "PENDING_REVIEW", "PROCESSING", "FAILED" -> true;
            default -> false;
        };
    }

    // ---------- 统计辅助 ----------

    private List<RepositorySummaryItem> repositorySummaries(List<DeliveryItem> items) {
        Map<String, List<CodeDeliveryItem>> byRepo = items.stream()
                .filter(i -> i instanceof CodeDeliveryItem)
                .map(i -> (CodeDeliveryItem) i)
                .flatMap(c -> c.getRepositories().stream()
                        .map(r -> Map.entry(r.getRepositoryId(), c)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        List<RepositorySummaryItem> result = new ArrayList<>();
        for (Map.Entry<String, List<CodeDeliveryItem>> entry : byRepo.entrySet()) {
            String repositoryId = entry.getKey();
            List<CodeDeliveryItem> related = entry.getValue();
            long total = related.size();
            long accepted = related.stream().filter(i -> "ACCEPTED".equals(i.getDisplayStatus())
                    || "DELIVERED".equals(i.getDisplayStatus())).count();
            long pending = related.stream().filter(i -> "DRAFT".equals(i.getDisplayStatus())
                    || "PENDING_REVIEW".equals(i.getDisplayStatus())
                    || "PROCESSING".equals(i.getDisplayStatus())).count();
            long failed = related.stream().filter(i -> "FAILED".equals(i.getDisplayStatus())
                    || "REJECTED".equals(i.getDisplayStatus())).count();
            String name = related.stream().map(CodeDeliveryItem::getRepositories).flatMap(List::stream)
                    .filter(r -> repositoryId.equals(r.getRepositoryId())).map(CodeDeliveryItem.RepositoryRef::getName)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            MergeRequestSummary mr = related.stream().map(CodeDeliveryItem::getMergeRequest)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            String deliveryStatus = related.stream().map(CodeDeliveryItem::getDeliveryStatus)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            result.add(new RepositorySummaryItem(repositoryId, name, total, accepted, pending, failed,
                    deliveryStatus, mr));
        }
        return result;
    }

    private List<RequirementGroupSummaryItem> groupSummaries(List<DeliveryItem> items) {
        Map<String, List<DeliveryItem>> byGroup = items.stream()
                .filter(i -> i.getRequirementGroup() != null)
                .collect(Collectors.groupingBy(i -> i.getRequirementGroup().getId()));
        List<RequirementGroupSummaryItem> result = new ArrayList<>();
        for (Map.Entry<String, List<DeliveryItem>> entry : byGroup.entrySet()) {
            List<DeliveryItem> related = entry.getValue();
            long pending = related.stream().filter(i -> "DRAFT".equals(i.getDisplayStatus())
                    || "PENDING_REVIEW".equals(i.getDisplayStatus())
                    || "PROCESSING".equals(i.getDisplayStatus())
                    || "FAILED".equals(i.getDisplayStatus())).count();
            String name = related.stream().map(i -> i.getRequirementGroup().getName())
                    .filter(Objects::nonNull).findFirst().orElse(null);
            result.add(new RequirementGroupSummaryItem(entry.getKey(), name, related.size(), pending));
        }
        return result;
    }

    // ---------- 摘要辅助 ----------

    private DeliveryItem.DeliverySource source(TaskEntity task, DiffReviewBatchEntity batch) {
        return new DeliveryItem.DeliverySource(id(task.getId()), task.getDisplayCode(), task.getTitle(),
                id(batch.getFinalCodingTaskRunId()), null, id(task.getTriggerMessageId()), null);
    }

    private DeliveryItem.RequirementGroupRef groupRef(RequirementGroupEntity group) {
        return group == null ? null : new DeliveryItem.RequirementGroupRef(id(group.getId()), group.getName());
    }

    private MergeRequestSummary mergeRequestSummary(MergeRequestEntity mr,
                                                    ProjectRepositoryEntity binding,
                                                    Map<UUID, GitHubRepositoryEntity> githubById) {
        GitHubRepositoryEntity repo = binding == null ? null : githubById.get(binding.getRepositoryId());
        String webUrl = repo == null || repo.getOwnerLogin() == null || repo.getName() == null
                ? null : "https://github.com/" + repo.getOwnerLogin() + "/" + repo.getName()
                + "/pull/" + mr.getProviderNumber();
        return new MergeRequestSummary(id(mr.getId()), mr.getProviderNumber(), mr.getTitle(), mr.getStatus(), webUrl);
    }

    private Map<UUID, GitHubRepositoryEntity> loadGithub(Collection<ProjectRepositoryEntity> bindings) {
        Set<UUID> repoIds = bindings.stream().map(ProjectRepositoryEntity::getRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return repoIds.isEmpty() ? Collections.emptyMap() : githubRepositories
                .selectList(Wrappers.<GitHubRepositoryEntity>lambdaQuery().in(GitHubRepositoryEntity::getId, repoIds))
                .stream().collect(Collectors.toMap(GitHubRepositoryEntity::getId, Function.identity()));
    }

    private String bindingName(ProjectRepositoryEntity binding, GitHubRepositoryEntity repo) {
        if (binding != null && binding.getDisplayName() != null && !binding.getDisplayName().isBlank()) {
            return binding.getDisplayName();
        }
        return repo == null ? null : repo.getName();
    }

    private UserSummary userSummary(UUID userId) {
        if (userId == null) {
            return null;
        }
        UserEntity user = users.selectById(userId);
        return user == null ? null
                : new UserSummary(user.getId().toString(), user.getDisplayName(), user.getAvatarUrl());
    }

    private String excerpt(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.length() <= EXCERPT_LIMIT ? trimmed : trimmed.substring(0, EXCERPT_LIMIT);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private UUID optionalUuid(String raw, String errorCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "筛选参数格式非法");
        }
    }

    private int clampLimit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1) {
            return 1;
        }
        return Math.min(value, MAX_LIMIT);
    }

    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标非法");
        }
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
