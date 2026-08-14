package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.dto.DiffReviewBatchResponse;
import qg.qgent.dto.RepositoryDelivery;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 总 Diff 批次逐仓库交付详情组装测试（成功仓库含真实 MR 摘要，失败仓库含脱敏原因）。 */
class DiffReviewBatchServiceTest {
    private final DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
    private final MergeRequestMapper mergeRequestMapper = mock(MergeRequestMapper.class);
    private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
    private final MergeRequestService mergeRequests = mock(MergeRequestService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final DiffSnapshotStorage snapshots = mock(DiffSnapshotStorage.class);

    private final DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, tasks, worktrees,
            repositories, githubRepositories, mergeRequestMapper, worker, mergeRequests, access, events, transactions,
            snapshots);

    @BeforeAll
    static void registerTableInfos() {
        // 纯 Mockito 单元测试无 Spring/MyBatis 上下文，Wrappers.lambdaQuery 需要实体 TableInfo 缓存。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DiffReviewBatchEntity.class);
        TableInfoHelper.initTableInfo(assistant, DiffEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, MergeRequestEntity.class);
    }

    @BeforeEach
    void stubDefaults() {
        when(access.requireProjectMember(any(), any())).thenReturn("PROJECT_MEMBER");
    }

    @Test
    void getReturnsRepositoryDeliveriesWithRealMrSummary() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), diffId = UUID.randomUUID(), bindingId = UUID.randomUUID();
        UUID githubId = UUID.randomUUID(), mrId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId);
        batch.setProjectId(projectId);
        batch.setTaskId(taskId);
        batch.setWorkspaceId(workspaceId);
        batch.setFinalCodingTaskRunId(UUID.randomUUID());
        batch.setReviewStatus("ACCEPTED");
        batch.setDeliveryStatus("PARTIALLY_DELIVERED");
        batch.setAggregateHash("hash");
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        when(batches.selectOne(any())).thenReturn(batch);

        DiffEntity diff = new DiffEntity();
        diff.setId(diffId);
        diff.setProjectId(projectId);
        diff.setTaskId(taskId);
        diff.setTaskRunId(UUID.randomUUID());
        diff.setWorkspaceId(workspaceId);
        diff.setProjectRepositoryId(bindingId);
        diff.setBaseCommit("abc123");
        diff.setSourceBranch("feat/login-api");
        diff.setStatus("ACCEPTED");
        diff.setDeliveryStatus("MR_CREATED");
        diff.setCreatedAt(now);
        diff.setUpdatedAt(now);
        when(diffs.selectList(any())).thenReturn(List.of(diff));

        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(bindingId);
        binding.setRepositoryId(githubId);
        binding.setDefaultBranch("main");
        binding.setDisplayName("auth-service");
        when(repositories.selectList(any())).thenReturn(List.of(binding));

        GitHubRepositoryEntity github = new GitHubRepositoryEntity();
        github.setId(githubId);
        github.setOwnerLogin("qgents");
        github.setName("auth-service");
        when(githubRepositories.selectList(any())).thenReturn(List.of(github));

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mrId);
        mr.setProjectRepositoryId(bindingId);
        mr.setTaskId(taskId);
        mr.setProvider("GITHUB");
        mr.setProviderNumber(128L);
        mr.setTitle("feat: implement login API");
        mr.setStatus("OPEN");
        mr.setCreatedAt(now);
        when(mergeRequestMapper.selectList(any())).thenReturn(List.of(mr));

        DiffReviewBatchResponse response = service.get(projectId, taskId, UUID.randomUUID());

        RepositoryDelivery delivery = response.getRepositoryDeliveries().getFirst();
        assertEquals("auth-service", delivery.getRepositoryName());
        assertEquals("MR_CREATED", delivery.getDeliveryStatus());
        assertEquals(diffId.toString(), delivery.getDiffId());
        assertEquals(128L, delivery.getMergeRequest().getNumber());
        assertEquals("feat: implement login API", delivery.getMergeRequest().getTitle());
        assertEquals("https://github.com/qgents/auth-service/pull/128", delivery.getMergeRequest().getWebUrl());
        assertEquals("OPEN", delivery.getMergeRequest().getStatus());
    }
}
