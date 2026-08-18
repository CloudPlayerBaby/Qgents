package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
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
 * 项目级工作分支只读视图。
 * <p>
 * 该服务只聚合已持久化的 Workspace、Task、Diff、MR 和 TestRun 事实，不扫描 GitHub 全量分支，
 * 也不推断冲突、落后或保护状态。工作分支可以被续接 Task 复用，故所有关联均为聚合关系。
 */
@Service
public class WorkBranchService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> COMPLETED_TEST_STATUSES = Set.of("PASSED", "FAILED", "CANCELLED");

    private final WorkspaceRepositoryMapper worktrees;
    private final TaskMapper tasks;
    private final DiffMapper diffs;
    private final MergeRequestMapper mergeRequests;
    private final TestRunMapper testRuns;
    private final RequirementGroupMapper groups;
    private final ProjectRepositoryMapper projectRepositories;
    private final ProjectAccessService access;
    private GitHubRepositoryMapper githubRepositories;

    public WorkBranchService(WorkspaceRepositoryMapper worktrees, TaskMapper tasks, DiffMapper diffs,
                             MergeRequestMapper mergeRequests, TestRunMapper testRuns,
                             RequirementGroupMapper groups, ProjectRepositoryMapper projectRepositories,
                             ProjectAccessService access) {
        this.worktrees = worktrees;
        this.tasks = tasks;
        this.diffs = diffs;
        this.mergeRequests = mergeRequests;
        this.testRuns = testRuns;
        this.groups = groups;
        this.projectRepositories = projectRepositories;
        this.access = access;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGithubRepositories(GitHubRepositoryMapper githubRepositories) {
        this.githubRepositories = githubRepositories;
    }

    /**
     * 分页查询项目内 Qgents 可追溯的工作分支。
     */
    public ApiPageResponse<WorkBranchResponse> list(UUID projectId, UUID actor, UUID repositoryId,
                                                     UUID requirementGroupId, String cursor, Integer limit,
                                                     String requestId) {
        access.requireProjectMember(projectId, actor);
        requireRepository(projectId, repositoryId);
        requireRequirementGroup(projectId, requirementGroupId);

        List<WorkspaceRepositoryEntity> allWorktrees = worktrees.selectByProject(projectId, repositoryId);
        if (allWorktrees.isEmpty()) {
            return emptyPage(requestId);
        }
        List<UUID> workspaceIds = allWorktrees.stream().map(WorkspaceRepositoryEntity::getWorkspaceId).distinct().toList();
        List<TaskEntity> projectTasks = tasks.selectList(Wrappers.<TaskEntity>query()
                .eq("project_id", projectId)
                .in("workspace_id", workspaceIds));
        Map<UUID, List<TaskEntity>> tasksByWorkspace = projectTasks.stream()
                .collect(Collectors.groupingBy(TaskEntity::getWorkspaceId));

        List<WorkspaceRepositoryEntity> filteredWorktrees = allWorktrees.stream()
                .filter(worktree -> requirementGroupId == null || tasksByWorkspace
                        .getOrDefault(worktree.getWorkspaceId(), List.of()).stream()
                        .anyMatch(task -> requirementGroupId.equals(task.getRequirementGroupId())))
                .toList();
        if (filteredWorktrees.isEmpty()) {
            return emptyPage(requestId);
        }

        Set<UUID> branchRepositoryIds = filteredWorktrees.stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).collect(Collectors.toSet());
        Set<UUID> branchWorkspaceIds = filteredWorktrees.stream()
                .map(WorkspaceRepositoryEntity::getWorkspaceId).collect(Collectors.toSet());
        List<DiffEntity> projectDiffs = diffs.selectList(Wrappers.<DiffEntity>query()
                .eq("project_id", projectId)
                .in("workspace_id", branchWorkspaceIds)
                .in("project_repository_id", branchRepositoryIds));
        List<MergeRequestEntity> branchMergeRequests = mergeRequests.selectList(Wrappers.<MergeRequestEntity>query()
                .in("project_repository_id", branchRepositoryIds));
        List<TestRunEntity> completedTests = testRuns.selectList(Wrappers.<TestRunEntity>query()
                .eq("project_id", projectId)
                .in("project_repository_id", branchRepositoryIds)
                .in("status", COMPLETED_TEST_STATUSES));

        Map<BranchKey, List<WorkspaceRepositoryEntity>> worktreesByBranch = filteredWorktrees.stream()
                .collect(Collectors.groupingBy(WorkBranchService::keyOf));
        Map<BranchKey, List<DiffEntity>> diffsByBranch = projectDiffs.stream()
                .filter(diff -> diff.getSourceBranch() != null)
                .collect(Collectors.groupingBy(diff -> new BranchKey(diff.getProjectRepositoryId(), diff.getSourceBranch())));
        Map<BranchKey, List<MergeRequestEntity>> mrsByBranch = branchMergeRequests.stream()
                .filter(mr -> mr.getSourceBranch() != null)
                .collect(Collectors.groupingBy(mr -> new BranchKey(mr.getProjectRepositoryId(), mr.getSourceBranch())));

        Set<UUID> groupIds = projectTasks.stream().map(TaskEntity::getRequirementGroupId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, RequirementGroupEntity> groupById = groupIds.isEmpty() ? Map.of() : groups.selectBatchIds(groupIds).stream()
                .collect(Collectors.toMap(RequirementGroupEntity::getId, Function.identity()));

        List<BranchView> views = worktreesByBranch.entrySet().stream()
                .map(entry -> view(entry.getKey(), entry.getValue(), tasksByWorkspace,
                        diffsByBranch.getOrDefault(entry.getKey(), List.of()),
                        mrsByBranch.getOrDefault(entry.getKey(), List.of()), completedTests, groupById))
                .sorted(Comparator.comparing(BranchView::lastActivity, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(view -> view.key().projectRepositoryId().toString())
                        .thenComparing(view -> view.key().name()))
                .toList();
        return page(views, cursor, clampLimit(limit), requestId);
    }

    private BranchView view(BranchKey key, List<WorkspaceRepositoryEntity> branchWorktrees,
                            Map<UUID, List<TaskEntity>> tasksByWorkspace, List<DiffEntity> branchDiffs,
                            List<MergeRequestEntity> branchMrs, List<TestRunEntity> completedTests,
                            Map<UUID, RequirementGroupEntity> groupById) {
        List<TaskEntity> relatedTasks = branchWorktrees.stream()
                .flatMap(worktree -> tasksByWorkspace.getOrDefault(worktree.getWorkspaceId(), List.of()).stream())
                .distinct().toList();
        TaskEntity latestTask = relatedTasks.stream().max(taskOrder()).orElse(null);
        WorkspaceRepositoryEntity latestWorktree = branchWorktrees.stream().max(worktreeOrder()).orElseThrow();
        if (latestTask != null) {
            latestWorktree = branchWorktrees.stream()
                    .filter(worktree -> latestTask.getWorkspaceId().equals(worktree.getWorkspaceId()))
                    .max(worktreeOrder()).orElse(latestWorktree);
        }
        DiffEntity latestDiff = branchDiffs.stream().max(diffOrder()).orElse(null);
        MergeRequestEntity latestMr = branchMrs.stream().max(mrOrder()).orElse(null);
        MergeRequestEntity openMr = branchMrs.stream().filter(mr -> "OPEN".equals(mr.getStatus()))
                .max(mrOrder()).orElse(null);
        boolean locked = branchMrs.stream().anyMatch(mr -> !"MERGED".equals(mr.getStatus()));
        String developmentStatus = locked ? "LOCKED_BY_OPEN_MR"
                : (latestMr != null && "MERGED".equals(latestMr.getStatus()) ? "MERGED" : "AVAILABLE");
        WorkBranchVerificationRef verification = latestVerification(completedTests, key.projectRepositoryId(),
                latestWorktree.getHeadCommit());
        List<WorkBranchRequirementGroupRef> requirementGroups = relatedTasks.stream()
                .map(TaskEntity::getRequirementGroupId).filter(Objects::nonNull).distinct()
                .map(groupById::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(RequirementGroupEntity::getName, Comparator.nullsLast(String::compareTo)))
                .map(group -> new WorkBranchRequirementGroupRef(id(group.getId()), group.getName())).toList();
        WorkBranchResponse response = new WorkBranchResponse(id(key.projectRepositoryId()), key.name(),
                id(latestWorktree.getWorkspaceId()), latestWorktree.getHeadCommit(), taskRef(latestTask),
                requirementGroups, diffRef(latestDiff), mrRef(openMr), mrRef(latestMr), developmentStatus,
                !locked, locked ? "WORK_BRANCH_LOCKED_BY_OPEN_MR" : null, verification);
        LocalDateTime lastActivity = maxTime(latestTask == null ? null : latestTask.getUpdatedAt(),
                latestWorktree.getUpdatedAt(), latestDiff == null ? null : latestDiff.getCreatedAt(),
                openMr == null ? null : openMr.getProviderUpdatedAt());
        return new BranchView(key, response, lastActivity);
    }

    private WorkBranchVerificationRef latestVerification(List<TestRunEntity> completedTests, UUID repositoryId,
                                                          String headCommit) {
        if (headCommit == null || headCommit.isBlank()) {
            return null;
        }
        return completedTests.stream()
                .filter(run -> repositoryId.equals(run.getProjectRepositoryId()))
                .filter(run -> headCommit.equals(run.getExecutionSourceRef()))
                .max(Comparator.comparing(TestRunEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TestRunEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(run -> new WorkBranchVerificationRef("TEST_RUN", run.getStatus(), run.getExecutionSourceRef(),
                        iso(run.getUpdatedAt())))
                .orElse(null);
    }

    private ApiPageResponse<WorkBranchResponse> page(List<BranchView> views, String cursor, int limit,
                                                      String requestId) {
        int start = startAfter(views, cursor);
        int end = Math.min(start + limit, views.size());
        List<BranchView> slice = views.subList(start, end);
        boolean hasMore = end < views.size();
        String nextCursor = hasMore ? encodeCursor(slice.getLast().key()) : null;
        return new ApiPageResponse<>(slice.stream().map(BranchView::response).toList(),
                new PageMeta(nextCursor, hasMore), requestId);
    }

    private int startAfter(List<BranchView> views, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        BranchKey key = decodeCursor(cursor);
        for (int index = 0; index < views.size(); index++) {
            if (views.get(index).key().equals(key)) {
                return index + 1;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标已失效，请从第一页重新加载");
    }

    private void requireRepository(UUID projectId, UUID repositoryId) {
        if (repositoryId == null) {
            return;
        }
        ProjectRepositoryEntity repository = projectRepositories.selectById(repositoryId);
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或无权访问");
        }
    }

    private void requireRequirementGroup(UUID projectId, UUID requirementGroupId) {
        if (requirementGroupId == null) {
            return;
        }
        RequirementGroupEntity group = groups.selectById(requirementGroupId);
        if (group == null || !projectId.equals(group.getProjectId()) || !"REQUIREMENT".equals(group.getGroupType())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REQUIREMENT_GROUP_NOT_FOUND", "需求群不存在或无权访问");
        }
    }

    private ApiPageResponse<WorkBranchResponse> emptyPage(String requestId) {
        return new ApiPageResponse<>(List.of(), new PageMeta(null, false), requestId);
    }

    private Comparator<TaskEntity> taskOrder() {
        return Comparator.comparing(TaskEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<WorkspaceRepositoryEntity> worktreeOrder() {
        return Comparator.comparing(WorkspaceRepositoryEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkspaceRepositoryEntity::getWorkspaceId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<DiffEntity> diffOrder() {
        return Comparator.comparing(DiffEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DiffEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<MergeRequestEntity> mrOrder() {
        return Comparator.comparing(MergeRequestEntity::getProviderUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MergeRequestEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MergeRequestEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private String encodeCursor(BranchKey key) {
        String raw = key.projectRepositoryId() + "\n" + key.name();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private BranchKey decodeCursor(String cursor) {
        try {
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 2 || fields[1].isBlank()) {
                throw new IllegalArgumentException();
            }
            return new BranchKey(UUID.fromString(fields[0]), fields[1]);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private WorkBranchTaskRef taskRef(TaskEntity task) {
        return task == null ? null : new WorkBranchTaskRef(id(task.getId()), task.getDisplayCode(), task.getTitle(),
                iso(task.getUpdatedAt()));
    }

    private WorkBranchDiffRef diffRef(DiffEntity diff) {
        return diff == null ? null : new WorkBranchDiffRef(id(diff.getId()), id(diff.getTaskId()), diff.getStatus(),
                diff.getChangeStats(), iso(diff.getCreatedAt()));
    }

    private WorkBranchMergeRequestRef mrRef(MergeRequestEntity mr) {
        if (mr == null) {
            return null;
        }
        String webUrl = null;
        if (githubRepositories != null) {
            ProjectRepositoryEntity binding = projectRepositories.selectById(mr.getProjectRepositoryId());
            GitHubRepositoryEntity repository = binding == null ? null : githubRepositories.selectById(binding.getRepositoryId());
            if (repository != null && repository.getOwnerLogin() != null && repository.getName() != null
                    && mr.getProviderNumber() != null) {
                webUrl = "https://github.com/" + repository.getOwnerLogin() + "/" + repository.getName()
                        + "/pull/" + mr.getProviderNumber();
            }
        }
        return new WorkBranchMergeRequestRef(id(mr.getId()), mr.getProviderNumber(), mr.getStatus(), webUrl);
    }

    private static BranchKey keyOf(WorkspaceRepositoryEntity worktree) {
        return new BranchKey(worktree.getProjectRepositoryId(), worktree.getSourceBranch());
    }

    private LocalDateTime maxTime(LocalDateTime... values) {
        return Arrays.stream(values).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record BranchKey(UUID projectRepositoryId, String name) {
    }

    private record BranchView(BranchKey key, WorkBranchResponse response, LocalDateTime lastActivity) {
    }
}
