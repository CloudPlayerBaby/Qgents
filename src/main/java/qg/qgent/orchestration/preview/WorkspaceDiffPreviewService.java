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
import qg.qgent.dto.WorkspaceDiffPreviewResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceDiffPreviewEntity;
import qg.qgent.entity.WorkspaceDiffPreviewRevisionEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceDiffPreviewMapper;
import qg.qgent.mapper.WorkspaceDiffPreviewRevisionMapper;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.service.DiffSnapshotStorage;
import qg.qgent.service.EventService;
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
    private final ProjectAccessService access;
    private final TaskMapper tasks;
    private final boolean workerEnabled;

    public WorkspaceDiffPreviewService(WorkspaceDiffAccess diffAccess, EventService eventService,
                                       DiffSnapshotStorage snapshots,
                                       WorkspaceDiffPreviewMapper previewMapper,
                                       WorkspaceDiffPreviewRevisionMapper revisionMapper,
                                       ProjectAccessService access, TaskMapper tasks,
                                       @Value("${app.worker.enabled:false}") boolean workerEnabled) {
        this.diffAccess = diffAccess;
        this.eventService = eventService;
        this.snapshots = snapshots;
        this.previewMapper = previewMapper;
        this.revisionMapper = revisionMapper;
        this.access = access;
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
        publish(projectId, taskId, taskRunId, workspaceId, revision, diff, now);
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
     */
    private void publish(UUID projectId, UUID taskId, UUID taskRunId, UUID workspaceId, long revision,
                         GitDiffResult diff, LocalDateTime now) {
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
        payload.put("updatedAt", now.toString());
        eventService.publish(projectId, null, EVENT_TYPE, workspaceId.toString(), payload);
    }

    // ---- 阶段 E：只读查询接口（项目成员可读） ----

    /**
     * 查询指定任务的实时 Diff Preview：缺省返回最新修订，可选指定 revision。
     * 鉴权后按 task→workspace 归属解析修订；patch 从受控快照读取，快照已清理时返回 null。
     */
    public WorkspaceDiffPreviewResponse preview(UUID projectId, UUID taskId, UUID actor, Long revision) {
        access.requireProjectMember(projectId, actor);
        WorkspaceDiffPreviewRevisionEntity rev = requireRevision(projectId, taskId, revision);
        return toResponse(rev, loadQuietly(rev.getSnapshotKey()));
    }

    /**
     * 查询指定任务的实时 Diff Preview 结构化文件列表（从受控 patch 解析，不落额外存储）。
     */
    public List<WorkspaceDiffPreviewFileResponse> files(UUID projectId, UUID taskId, UUID actor, Long revision) {
        access.requireProjectMember(projectId, actor);
        WorkspaceDiffPreviewRevisionEntity rev = requireRevision(projectId, taskId, revision);
        return DiffPatchFileParser.parse(loadQuietly(rev.getSnapshotKey()));
    }

    /**
     * 解析目标修订：校验项目成员（调用方已做）与 task→project 归属，按 workspaceId 查 revision 行。
     * 无修订 / task 归属不一致 / workspace 缺失 → 404，防枚举。
     */
    private WorkspaceDiffPreviewRevisionEntity requireRevision(UUID projectId, UUID taskId, Long revision) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId()) || task.getWorkspaceId() == null) {
            throw notFound();
        }
        WorkspaceDiffPreviewRevisionEntity rev = revisionMapper.selectOne(Wrappers
                .<WorkspaceDiffPreviewRevisionEntity>lambdaQuery()
                .eq(WorkspaceDiffPreviewRevisionEntity::getWorkspaceId, task.getWorkspaceId())
                .eq(revision != null, WorkspaceDiffPreviewRevisionEntity::getRevision, revision)
                .orderByDesc(WorkspaceDiffPreviewRevisionEntity::getRevision)
                .last("LIMIT 1"));
        if (rev == null) {
            throw notFound();
        }
        return rev;
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
