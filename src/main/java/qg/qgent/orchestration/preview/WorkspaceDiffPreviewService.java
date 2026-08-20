package qg.qgent.orchestration.preview;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.WorkspaceDiffPreviewFileResponse;
import qg.qgent.dto.WorkspaceDiffPreviewFileDetailResponse;
import qg.qgent.dto.WorkspaceDiffPreviewResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceDiffPreviewEntity;
import qg.qgent.entity.WorkspaceDiffPreviewRevisionEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceDiffPreviewMapper;
import qg.qgent.mapper.WorkspaceDiffPreviewRevisionMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.service.DiffSnapshotStorage;
import qg.qgent.service.EventService;
import qg.qgent.service.GroupService;
import qg.qgent.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace 实时 Diff Preview 服务（阶段 D）：Coding 每次成功写后调用
 * {@link #record}，把当前累积工作树 diff 落为一条单调递增 revision，并把 patch 存入受控
 * 快照存储；持久化成功后才发布 {@code workspace.diff-preview.updated} 事件（AGENTS.md：
 * 先落库再发布）。同 Workspace 同 workingTreeHash 幂等跳过，避免高频 patch 重复落库。
 * <p>
 * Preview 与正式 Diff 严格分离：只反映 Coding 写过程中的累积工作树变更，永不被当作
 * 已 commit/push/MR；正式 Diff 终态链路 {@code FinalDiffBundleService} 不受影响。
 * Worker 未启用或 diff 不可用时记日志跳过，不阻塞编排。
 */
@Slf4j
@Service
public class WorkspaceDiffPreviewService {

    /**
     * 预览修订保留时长（天），超期由 {@link #purgeExpired} 兜底清理（仿 EventService.purgeExpired）。
     */
    private static final long REVISION_RETENTION_DAYS = 7;
    private static final String EVENT_TYPE = "workspace.diff-preview.updated";

    private final WorkspaceDiffAccess diffAccess;
    private final EventService eventService;
    private final DiffSnapshotStorage snapshots;
    private final WorkspaceDiffPreviewMapper previewMapper;
    private final WorkspaceDiffPreviewRevisionMapper revisionMapper;
    private final WorkspaceRepositoryMapper workspaceRepositories;
    private final ProjectAccessService access;
    private final GroupService groups;
    private final TaskMapper tasks;
    private final boolean workerEnabled;

    public WorkspaceDiffPreviewService(WorkspaceDiffAccess diffAccess, EventService eventService,
                                       DiffSnapshotStorage snapshots,
                                       WorkspaceDiffPreviewMapper previewMapper,
                                       WorkspaceDiffPreviewRevisionMapper revisionMapper,
                                       WorkspaceRepositoryMapper workspaceRepositories,
                                       ProjectAccessService access, GroupService groups, TaskMapper tasks,
                                       @Value("${app.worker.enabled:false}") boolean workerEnabled) {
        this.diffAccess = diffAccess;
        this.eventService = eventService;
        this.snapshots = snapshots;
        this.previewMapper = previewMapper;
        this.revisionMapper = revisionMapper;
        this.workspaceRepositories = workspaceRepositories;
        this.access = access;
        this.groups = groups;
        this.tasks = tasks;
        this.workerEnabled = workerEnabled;
    }

    /**
     * 记录一次累积工作树 diff 预览。任何失败（diff 不可用、无 tree hash、快照存储失败）都
     * 记日志跳过，绝不抛出（Coding 主循环不得因预览失败中断）。
     */
    public void record(UUID projectId, UUID taskId, UUID taskRunId, UUID workspaceId) {
        if (!workerEnabled) {
            log.debug("workspace diff preview skipped (worker disabled), workspaceId={}", workspaceId);
            return;
        }
        GitDiffResult diff = diffAccess.diff(workspaceId);
        if (!diff.ok()) {
            log.warn("WORKSPACE_DIFF_PREVIEW_DIFF_UNAVAILABLE workspaceId={}: {}",
                    workspaceId, diff.error());
            return;
        }
        if (diff.workingTreeHash() == null || diff.workingTreeHash().isBlank()) {
            log.warn("WORKSPACE_DIFF_PREVIEW_NO_TREE_HASH workspaceId={}", workspaceId);
            return;
        }
        WorkspaceDiffPreviewRevisionEntity existing = revisionMapper.selectOne(Wrappers
                .<WorkspaceDiffPreviewRevisionEntity>lambdaQuery()
                .eq(WorkspaceDiffPreviewRevisionEntity::getWorkspaceId, workspaceId)
                .eq(WorkspaceDiffPreviewRevisionEntity::getWorkingTreeHash, diff.workingTreeHash())
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("workspace diff preview idempotent skip workspaceId={} hash={}",
                    workspaceId, diff.workingTreeHash());
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID revisionId = UuidV7.next();
        String snapshotKey;
        try {
            snapshotKey = snapshots.store(revisionId, diff.diff());
        } catch (RuntimeException e) {
            // 快照失败则本次预览不落库、不发事件（幂等窗口不产生半条记录）。
            log.warn("WORKSPACE_DIFF_PREVIEW_SNAPSHOT_FAILED workspaceId={}: {}",
                    workspaceId, e.getMessage());
            return;
        }
        long revision = nextRevision(workspaceId, projectId, taskId, now);
        WorkspaceDiffPreviewRevisionEntity rev = new WorkspaceDiffPreviewRevisionEntity();
        rev.setId(revisionId);
        rev.setProjectId(projectId);
        rev.setTaskId(taskId);
        rev.setTaskRunId(taskRunId);
        rev.setWorkspaceId(workspaceId);
        rev.setRevision(revision);
        rev.setBaseCommit(diff.baseCommit());
        rev.setWorkingTreeHash(diff.workingTreeHash());
        rev.setSnapshotKey(snapshotKey);
        rev.setFilesChanged(diff.filesChanged());
        rev.setAdditions(diff.additions());
        rev.setDeletions(diff.deletions());
        rev.setCreatedAt(now);
        revisionMapper.insert(rev);
        publish(projectId, taskId, taskRunId, workspaceId, revision, diff, now, groupId(projectId, taskId));
        log.info("workspace diff preview recorded workspaceId={} revision={} files={} add={} del={}",
                workspaceId, revision, diff.filesChanged(), diff.additions(), diff.deletions());
    }

    /**
     * 计算并推进最新修订号：头不存在则创建（revision=1），否则 latestRevision+1。
     */
    private long nextRevision(UUID workspaceId, UUID projectId, UUID taskId, LocalDateTime now) {
        WorkspaceDiffPreviewEntity header = previewMapper.selectOne(Wrappers
                .<WorkspaceDiffPreviewEntity>lambdaQuery()
                .eq(WorkspaceDiffPreviewEntity::getWorkspaceId, workspaceId).last("LIMIT 1"));
        long revision = 1;
        if (header == null) {
            header = new WorkspaceDiffPreviewEntity();
            header.setId(UuidV7.next());
            header.setWorkspaceId(workspaceId);
            header.setProjectId(projectId);
            header.setTaskId(taskId);
            header.setLatestRevision(1L);
            header.setUpdatedAt(now);
            previewMapper.insert(header);
        } else {
            long current = header.getLatestRevision() == null ? 0 : header.getLatestRevision();
            revision = current + 1;
            header.setLatestRevision(revision);
            header.setProjectId(projectId);
            header.setTaskId(taskId);
            header.setUpdatedAt(now);
            previewMapper.updateById(header);
        }
        return revision;
    }

    /**
     * 修订已持久化后才发布事件；payload 只含元数据，不携带 patch 或源码。
     * groupId 使用 Task 所属需求群，遵循需求群隔离（任务归属项目总群或群缺失时退化为项目级广播）。
     */
    private void publish(UUID projectId, UUID taskId, UUID taskRunId, UUID workspaceId, long revision,
                         GitDiffResult diff, LocalDateTime now, UUID groupId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventVersion", 1);
        payload.put("projectId", projectId);
        payload.put("taskId", taskId);
        payload.put("taskRunId", taskRunId);
        payload.put("workspaceId", workspaceId);
        payload.put("previewRevision", revision);
        payload.put("filesChanged", diff.filesChanged());
        payload.put("additions", diff.additions());
        payload.put("deletions", diff.deletions());
        // 与其他事件（Instant）保持一致：带 Z 的 UTC 时间戳，避免前端按本地时区误解析。
        payload.put("updatedAt", now.atOffset(ZoneOffset.UTC).toInstant().toString());
        eventService.publish(projectId, groupId, EVENT_TYPE, workspaceId.toString(), payload);
    }

    // ---- 阶段 E：只读查询接口（项目成员可读） ----

    /**
     * 查询指定任务的实时 Diff Preview：缺省返回最新修订，可选指定 revision。
     * 鉴权要求：项目成员 + Task 所属需求群成员（PROJECT_MAIN 项目总群任务退化为项目成员可见）；
     * 修订行按 projectId + taskId + workspaceId 精确归属，避免复用 Workspace 时读到其他 Task 的预览。
     */
    public WorkspaceDiffPreviewResponse preview(UUID projectId, UUID taskId, UUID actor, Long revision) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        requireGroupVisible(projectId, task, actor);
        WorkspaceDiffPreviewRevisionEntity rev = requireRevision(projectId, taskId, task.getWorkspaceId(), revision);
        return toResponse(rev, loadQuietly(rev.getSnapshotKey()));
    }

    /**
     * 查询指定任务的实时 Diff Preview 结构化文件列表（从受控 patch 解析，不落额外存储）。
     * 权限与归属校验同 {@link #preview}。
     */
    public List<WorkspaceDiffPreviewFileResponse> files(UUID projectId, UUID taskId, UUID actor, Long revision) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        requireGroupVisible(projectId, task, actor);
        WorkspaceDiffPreviewRevisionEntity rev = requireRevision(projectId, taskId, task.getWorkspaceId(), revision);
        return DiffPatchFileParser.parse(loadQuietly(rev.getSnapshotKey()));
    }

    /**
     * 查询指定 Preview revision 中单个仓库文件的 patch，避免客户端解析聚合 patch。
     */
    public WorkspaceDiffPreviewFileDetailResponse file(UUID projectId, UUID taskId, UUID actor,
                                                        Long revision, UUID repositoryId, String path) {
        if (repositoryId == null || path == null || path.isBlank() || !validRelativePath(path)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "repositoryId 和 path 参数不合法");
        }
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        requireGroupVisible(projectId, task, actor);
        WorkspaceRepositoryEntity repository = workspaceRepositories.selectByWorkspace(task.getWorkspaceId())
                .stream()
                .filter(value -> repositoryId.equals(value.getProjectRepositoryId()))
                .findFirst()
                .orElseThrow(this::notFound);
        WorkspaceDiffPreviewRevisionEntity rev = requireRevision(projectId, taskId, task.getWorkspaceId(), revision);
        String normalizedPath = path.replace('\\', '/');
        String patch = loadQuietly(rev.getSnapshotKey());
        DiffPatchFileParser.ParsedFile parsed = DiffPatchFileParser.find(patch, repository.getWorkspacePath(), normalizedPath)
                .orElseThrow(this::notFound);
        WorkspaceDiffPreviewFileResponse summary = parsed.file();
        return new WorkspaceDiffPreviewFileDetailResponse(rev.getRevision(), repositoryId.toString(), summary.getPath(),
                summary.getChangeType(), summary.getAdditions(), summary.getDeletions(), summary.getBinary(),
                summary.getBinary() ? null : parsed.patch());
    }

    /**
     * 校验 Task 归属：任务属于当前项目且已准备 Workspace，否则 404 防枚举。
     */
    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId()) || task.getWorkspaceId() == null) {
            throw notFound();
        }
        return task;
    }

    /**
     * 需求群可见性：Task 归属项目总群或需求群缺失时按项目成员可见性处理（调用方已校验），
     * 否则要求当前用户是该需求群成员。
     */
    private void requireGroupVisible(UUID projectId, TaskEntity task, UUID actor) {
        if (task.getRequirementGroupId() == null) {
            return;
        }
        groups.requireGroupMember(projectId, task.getRequirementGroupId(), actor);
    }

    /**
     * 解析目标修订：按 projectId + taskId + workspaceId（+ 可选 revision）精确匹配 revision 行，
     * 避免复用 Workspace 时跨 Task 读取其他预览。无匹配 → 404 防枚举。
     */
    private WorkspaceDiffPreviewRevisionEntity requireRevision(UUID projectId, UUID taskId, UUID workspaceId,
                                                               Long revision) {
        WorkspaceDiffPreviewRevisionEntity rev = revisionMapper.selectOne(Wrappers
                .<WorkspaceDiffPreviewRevisionEntity>lambdaQuery()
                .eq(WorkspaceDiffPreviewRevisionEntity::getProjectId, projectId)
                .eq(WorkspaceDiffPreviewRevisionEntity::getTaskId, taskId)
                .eq(WorkspaceDiffPreviewRevisionEntity::getWorkspaceId, workspaceId)
                .eq(revision != null, WorkspaceDiffPreviewRevisionEntity::getRevision, revision)
                .orderByDesc(WorkspaceDiffPreviewRevisionEntity::getRevision)
                .last("LIMIT 1"));
        if (rev == null) {
            throw notFound();
        }
        return rev;
    }

    /**
     * 读取 Task 的归属需求群；Task 缺失或不属于当前项目时返回 null（退化为项目级广播）。
     */
    private UUID groupId(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            return null;
        }
        return task.getRequirementGroupId();
    }

    private String loadQuietly(String snapshotKey) {
        if (snapshotKey == null || snapshotKey.isBlank()) {
            return null;
        }
        try {
            return snapshots.load(snapshotKey);
        } catch (RuntimeException e) {
            log.warn("WORKSPACE_DIFF_PREVIEW_SNAPSHOT_LOAD_FAILED snapshotKey={}: {}",
                    snapshotKey, e.getMessage());
            return null;
        }
    }

    private WorkspaceDiffPreviewResponse toResponse(WorkspaceDiffPreviewRevisionEntity rev, String patch) {
        return new WorkspaceDiffPreviewResponse(
                id(rev.getProjectId()), id(rev.getTaskId()), id(rev.getTaskRunId()), id(rev.getWorkspaceId()),
                rev.getRevision(), rev.getBaseCommit(), rev.getWorkingTreeHash(),
                rev.getFilesChanged(), rev.getAdditions(), rev.getDeletions(),
                patch, iso(rev.getCreatedAt()));
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_DIFF_PREVIEW_NOT_FOUND",
                "workspace diff preview not found");
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private boolean validRelativePath(String path) {
        String normalized = path.replace('\\', '/');
        return !normalized.startsWith("/") && !normalized.matches("^[A-Za-z]:/.*")
                && java.util.Arrays.stream(normalized.split("/"))
                .noneMatch(".."::equals);
    }

    /**
     * 每日兜底清理超期预览修订与长期未更新的头（仿 EventService.purgeExpired）。
     * 依赖 Qgents 全局 {@code @EnableScheduling} 生效。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(REVISION_RETENTION_DAYS);
            revisionMapper.delete(Wrappers.<WorkspaceDiffPreviewRevisionEntity>lambdaQuery()
                    .lt(WorkspaceDiffPreviewRevisionEntity::getCreatedAt, cutoff));
            previewMapper.delete(Wrappers.<WorkspaceDiffPreviewEntity>lambdaQuery()
                    .lt(WorkspaceDiffPreviewEntity::getUpdatedAt, cutoff));
            log.info("workspace diff preview purge complete cutoff={}", cutoff);
        } catch (Exception e) {
            log.warn("workspace diff preview purge failed: {}", e.getMessage());
        }
    }
}
