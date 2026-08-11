package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.DryRunCreateRequest;
import qg.qgent.dto.DryRunReportResponse;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.TestRunCreateRequest;
import qg.qgent.dto.TestRunResponse;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.mapper.TestsetMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 受控 Test Run 与 Dry Run 服务。
 * 仅管理配置与状态，真实执行由执行服务承担（202 接缝）；testsetIds 必须属于该仓库且为 ENABLED，
 * 受保护分支的必选测试集由分支门禁决定，客户端不能传入较少测试集跳过。
 * 创建为受理接缝：持久化 QUEUED 并返回，真实执行由执行服务（TODO 接缝）推进状态与写入结果；
 * 受保护分支必选测试集暂以仓库默认分支的 branch config 为准；后续由 TaskRepository.baseRef 精确校验。
 */
@Service
public class TestRunService {
    private final TestRunMapper testRunMapper;
    private final DryRunMapper dryRunMapper;
    private final ProjectRepositoryMapper repositoryMapper;
    private final TestsetMapper testsetMapper;
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    private final ProjectAccessService projectAccess;
    private final EventService eventService;

    public TestRunService(TestRunMapper testRunMapper, DryRunMapper dryRunMapper,
            ProjectRepositoryMapper repositoryMapper, TestsetMapper testsetMapper,
            RepositoryBranchConfigMapper branchConfigMapper,
            RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
            EventService eventService) {
        this.testRunMapper = testRunMapper;
        this.dryRunMapper = dryRunMapper;
        this.repositoryMapper = repositoryMapper;
        this.testsetMapper = testsetMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
    }

    /**
     * 发起受控测试运行。
     * 校验 repositoryId 归属项目、taskId 与 ref 二选一、testsetIds 属于仓库且 ENABLED，
     * 并确保覆盖受保护分支必选测试集；受理后持久化 QUEUED 并发布 test-run.updated。
     *
     * @return 新测试运行摘要（受理态，真实执行后续由执行服务推进）
     */
    @Transactional
    public TestRunResponse createTestRun(UUID projectId, UUID userId, TestRunCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        ProjectRepositoryEntity repo = requireRepository(projectId, request.getRepositoryId());
        boolean hasTask = request.getTaskId() != null;
        boolean hasRef = request.getRef() != null && !request.getRef().isBlank();
        if (hasTask == hasRef) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEST_RUN_TARGET",
                    "taskId 与 ref 必须二选一");
        }
        validateTestsets(projectId, request);
        enforceRequiredTestsets(repo, request);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TestRunEntity run = new TestRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(projectId);
        run.setProjectRepositoryId(request.getRepositoryId());
        run.setTaskId(request.getTaskId());
        run.setRef(request.getRef());
        run.setTestsetIds(request.getTestsetIds().stream().map(UUID::toString).toList());
        run.setStatus("QUEUED");
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        testRunMapper.insert(run);
        // TODO 接缝：受理后由执行服务排队并在安全环境执行，执行完成前 status/summary 保持不变
        publishTestRunUpdated(run);
        return toTestRun(run);
    }

    /**
     * 获取测试运行状态、用例摘要和产物引用。
     */
    public TestRunResponse testRun(UUID projectId, UUID testRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TestRunEntity run = testRunMapper.selectById(testRunId);
        if (run == null || !run.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEST_RUN_NOT_FOUND", "测试运行不存在或不可见");
        }
        return toTestRun(run);
    }

    /**
     * 针对源分支和目标分支发起合并前试运行。
     * 校验 repositoryId 归属项目；受理后持久化 QUEUED 并发布 dry-run.updated。
     */
    @Transactional
    public DryRunResponse createDryRun(UUID projectId, UUID userId, DryRunCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        requireRepository(projectId, request.getRepositoryId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DryRunEntity run = new DryRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(projectId);
        run.setProjectRepositoryId(request.getRepositoryId());
        run.setTaskId(request.getTaskId());
        run.setSourceRef(request.getSourceRef());
        run.setTargetBranch(request.getTargetBranch());
        run.setStatus("QUEUED");
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        dryRunMapper.insert(run);
        // TODO 接缝：受理后由执行服务执行合并前试运行并写入 report
        publishDryRunUpdated(run);
        return toDryRun(run);
    }

    /**
     * 获取试运行报告和冲突、测试摘要。
     */
    public DryRunReportResponse dryRunReport(UUID projectId, UUID dryRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        DryRunEntity run = dryRunMapper.selectById(dryRunId);
        if (run == null || !run.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DRY_RUN_NOT_FOUND", "试运行不存在或不可见");
        }
        return new DryRunReportResponse(id(run.getId()), run.getStatus(), run.getReport(), iso(run.getCreatedAt()));
    }

    // ---------- 私有辅助 ----------

    private ProjectRepositoryEntity requireRepository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repo = repositoryMapper.selectById(repositoryId);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
        return repo;
    }

    /** 校验请求的 testsetIds 均属于该仓库且为 ENABLED。 */
    private void validateTestsets(UUID projectId, TestRunCreateRequest request) {
        for (UUID testsetId : request.getTestsetIds()) {
            TestsetEntity testset = testsetMapper.selectById(testsetId);
            if (testset == null || !testset.getProjectId().equals(projectId)
                    || testset.getProjectRepositoryId() == null
                    || !testset.getProjectRepositoryId().equals(request.getRepositoryId())
                    || !"ENABLED".equals(testset.getStatus())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TESTSET_NOT_ELIGIBLE",
                        "testsetId " + testsetId + " 必须属于该仓库且为 ENABLED");
            }
        }
    }

    /** 校验受保护分支必选测试集未被跳过；暂以仓库默认分支的 branch config 为准。 */
    private void enforceRequiredTestsets(ProjectRepositoryEntity repo, TestRunCreateRequest request) {
        RepositoryBranchConfigEntity config = branchConfigMapper.selectOne(
                Wrappers.<RepositoryBranchConfigEntity>lambdaQuery()
                        .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, repo.getId())
                        .eq(RepositoryBranchConfigEntity::getBranchName, repo.getDefaultBranch()));
        if (config == null) {
            return;
        }
        Set<UUID> requested = request.getTestsetIds().stream().collect(Collectors.toSet());
        for (RepositoryBranchConfigTestsetEntity relation : branchConfigTestsetMapper
                .selectByBranchConfigId(config.getId())) {
            if (!requested.contains(relation.getTestsetId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TESTSET_REQUIRED",
                        "受保护分支必选测试集不可跳过：" + relation.getTestsetId());
            }
        }
    }

    private void publishTestRunUpdated(TestRunEntity run) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", run.getProjectId());
        p.put("testRunId", run.getId());
        p.put("repositoryId", run.getProjectRepositoryId());
        if (run.getTaskId() != null) {
            p.put("taskId", run.getTaskId());
        }
        p.put("ref", run.getRef());
        p.put("status", run.getStatus());
        p.put("sequence", 0);
        p.put("timestamp", Instant.now().toString());
        eventService.publish(run.getProjectId(), null, "test-run.updated", run.getId().toString(), p);
    }

    private void publishDryRunUpdated(DryRunEntity run) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", run.getProjectId());
        p.put("dryRunId", run.getId());
        p.put("repositoryId", run.getProjectRepositoryId());
        if (run.getTaskId() != null) {
            p.put("taskId", run.getTaskId());
        }
        p.put("sourceRef", run.getSourceRef());
        p.put("targetBranch", run.getTargetBranch());
        p.put("status", run.getStatus());
        p.put("sequence", 0);
        p.put("timestamp", Instant.now().toString());
        eventService.publish(run.getProjectId(), null, "dry-run.updated", run.getId().toString(), p);
    }

    private TestRunResponse toTestRun(TestRunEntity run) {
        return new TestRunResponse(id(run.getId()), id(run.getProjectId()), id(run.getProjectRepositoryId()),
                run.getRef(), run.getTestsetIds(), run.getStatus(), run.getSummary(),
                id(run.getCreatedBy()), iso(run.getCreatedAt()));
    }

    private DryRunResponse toDryRun(DryRunEntity run) {
        return new DryRunResponse(id(run.getId()), id(run.getProjectId()), id(run.getProjectRepositoryId()),
                run.getSourceRef(), run.getTargetBranch(), run.getStatus(),
                run.getReport(), id(run.getCreatedBy()), iso(run.getCreatedAt()));
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
