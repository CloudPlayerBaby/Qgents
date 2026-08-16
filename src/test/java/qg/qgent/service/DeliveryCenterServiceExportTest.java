package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.entity.MemoryMessageSourceEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.SkillEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MemoryMessageSourceMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.SkillMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.UserMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DeliveryCenterService#exportCsv} 的聚焦测试（契约成员 B P2）。
 * <p>
 * 覆盖：UTF-8 BOM 与表头、RFC 4180 转义、摘要截断与完整内容不泄漏、
 * type 筛选、CODE 变更统计列、项目成员权限校验。
 */
class DeliveryCenterServiceExportTest {

    private final DiffReviewBatchMapper diffBatches = mock(DiffReviewBatchMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
    private final MemoryMapper memories = mock(MemoryMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final MemoryMessageSourceMapper memorySources = mock(MemoryMessageSourceMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);

    private final DeliveryCenterService service = new DeliveryCenterService(
            diffBatches, diffs, mergeRequests, tasks, groups, projectRepositories, worktrees,
            githubRepositories, memories, skills, users, memorySources, messages, access);

    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);

    @BeforeAll
    static void registerTableInfos() {
        // 纯 Mockito 单元测试无 Spring/MyBatis 上下文，Wrappers.lambdaQuery 需要实体 TableInfo 缓存。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DiffReviewBatchEntity.class);
        TableInfoHelper.initTableInfo(assistant, DiffEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, RequirementGroupEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, MergeRequestEntity.class);
        TableInfoHelper.initTableInfo(assistant, MemoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, MemoryMessageSourceEntity.class);
        TableInfoHelper.initTableInfo(assistant, MessageEntity.class);
        TableInfoHelper.initTableInfo(assistant, SkillEntity.class);
        TableInfoHelper.initTableInfo(assistant, UserEntity.class);
    }

    private void stubEmptyProject() {
        when(diffBatches.selectList(any())).thenReturn(List.of());
        when(memories.selectList(any())).thenReturn(List.of());
        when(skills.selectList(any())).thenReturn(List.of());
    }

    @Test
    void emptyProjectReturnsBomAndHeaderOnly() {
        stubEmptyProject();

        String csv = service.exportCsv(projectId, actor, null, null, null, null, null);

        assertTrue(csv.startsWith("\uFEFF"), "CSV 应以 UTF-8 BOM 开头");
        assertTrue(csv.contains("类型,标题,摘要,展示状态,资源状态,需求群,来源任务编号,来源任务标题,"
                + "创建人,审核人,驳回原因,创建时间,审核时间,更新时间,变更文件数,新增行数,删除行数,仓库"));
        assertEquals(1, csv.lines().count(), "空数据集只应返回表头行");
    }

    @Test
    void memoryRowEscapesCommaAndNeverLeaksFullContent() {
        stubEmptyProject();
        UserEntity creator = new UserEntity();
        creator.setId(actor);
        creator.setDisplayName("张三");
        MemoryEntity memory = new MemoryEntity();
        memory.setId(UUID.randomUUID());
        memory.setProjectId(projectId);
        memory.setCreatedBy(actor);
        memory.setTitle("部署指南, 含逗号");
        String secretBody = "完整正文不应该出现在导出中-" + "x".repeat(500);
        memory.setContent(secretBody);
        memory.setStatus("APPROVED");
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        when(memories.selectList(any())).thenReturn(List.of(memory));
        when(memorySources.selectByMemoryIds(any())).thenReturn(List.of());
        when(users.selectById(actor)).thenReturn(creator);

        String csv = service.exportCsv(projectId, actor, null, null, null, null, null);

        assertTrue(csv.contains("\"部署指南, 含逗号\""), "含逗号字段应被双引号包裹");
        assertTrue(csv.contains("张三"));
        assertTrue(csv.contains("ACCEPTED"), "APPROVED 应派生为 ACCEPTED 展示状态");
        assertFalse(csv.contains(secretBody), "不得导出完整 Memory 内容（摘要仅前 200 字符）");
        assertTrue(csv.contains(secretBody.substring(0, 200)), "摘要应截断保留前 200 字符");
    }

    @Test
    void typeFilterOnlyExportsRequestedResource() {
        stubEmptyProject();
        MemoryEntity memory = new MemoryEntity();
        memory.setId(UUID.randomUUID());
        memory.setProjectId(projectId);
        memory.setCreatedBy(actor);
        memory.setTitle("MEMORY-标题");
        memory.setContent("m");
        memory.setStatus("APPROVED");
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        SkillEntity skill = new SkillEntity();
        skill.setId(UUID.randomUUID());
        skill.setProjectId(projectId);
        skill.setCreatedBy(actor);
        skill.setName("SKILL-名称");
        skill.setContent("s");
        skill.setStatus("PUBLISHED");
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        when(memories.selectList(any())).thenReturn(List.of(memory));
        when(memorySources.selectByMemoryIds(any())).thenReturn(List.of());
        when(skills.selectList(any())).thenReturn(List.of(skill));

        String csv = service.exportCsv(projectId, actor, null, "SKILL", null, null, null);

        assertTrue(csv.contains("SKILL-名称"));
        assertFalse(csv.contains("MEMORY-标题"), "type=SKILL 时不得导出 MEMORY");
    }

    @Test
    void codeRowExportsDiffStatsColumns() {
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId);
        batch.setProjectId(projectId);
        batch.setTaskId(taskId);
        batch.setWorkspaceId(workspaceId);
        batch.setReviewStatus("ACCEPTED");
        batch.setDeliveryStatus("DELIVERED");
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);
        task.setCreatedBy(actor);
        task.setTitle("实现登录功能");
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID());
        diff.setProjectRepositoryId(UUID.randomUUID());
        diff.setReviewBatchId(batchId);
        diff.setChangeStats(Map.of("files", 3, "additions", 10, "deletions", 2));
        diff.setCreatedAt(now);
        diff.setUpdatedAt(now);
        when(diffBatches.selectList(any())).thenReturn(List.of(batch));
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(mergeRequests.selectList(any())).thenReturn(List.of());
        when(groups.selectList(any())).thenReturn(List.of());
        when(projectRepositories.selectList(any())).thenReturn(List.of());
        when(worktrees.selectByWorkspaces(any())).thenReturn(List.of());
        when(githubRepositories.selectList(any())).thenReturn(List.of());
        when(memories.selectList(any())).thenReturn(List.of());
        when(skills.selectList(any())).thenReturn(List.of());

        String csv = service.exportCsv(projectId, actor, null, null, null, null, null);

        assertTrue(csv.contains("CODE"), "应包含 CODE 类型");
        assertTrue(csv.contains("实现登录功能"));
        assertTrue(csv.contains("3,10,2"), "应导出变更文件数/新增/删除列");
        assertTrue(csv.contains("DELIVERED"), "ACCEPTED+DELIVERED 应派生为 DELIVERED 展示状态");
    }

    @Test
    void nonMemberIsRejected() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "NOT_PROJECT_MEMBER", "非项目成员"))
                .when(access).requireProjectMember(projectId, actor);

        assertThrows(ApiException.class,
                () -> service.exportCsv(projectId, actor, null, null, null, null, null));
        verifyNoInteractions(diffBatches);
    }
}
