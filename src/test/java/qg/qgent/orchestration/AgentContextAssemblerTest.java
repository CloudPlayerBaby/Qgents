package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextRepository;
import qg.qgent.dto.ContextSkill;
import qg.qgent.dto.GroupContext;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.service.ContextService;
import qg.qgent.orchestration.TaskContextSnapshotCodec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentContextAssembler 上下文接线测试：确认 ContextService 快照（群聊/Skill/Memory）被填充进
 * AgentInput、limit 恒为 50、组装失败仅返回 null 不抛出；续作任务会把源 Diff 摘要注入对话头。
 */
class AgentContextAssemblerTest {

    private final ContextService contextService = mock(ContextService.class);
    private final TaskContextSnapshotCodec contextSnapshotCodec = new TaskContextSnapshotCodec(new ObjectMapper());
    private final DiffReviewBatchMapper diffBatches = mock(DiffReviewBatchMapper.class);
    private final DiffMapper diffMapper = mock(DiffMapper.class);
    private final WorkspaceRepositoryMapper workspaceRepositories = mock(WorkspaceRepositoryMapper.class);
    private final AgentContextAssembler assembler =
            new AgentContextAssembler(contextService, contextSnapshotCodec, diffBatches, diffMapper,
                    workspaceRepositories);

    private TaskEntity task() {
        TaskEntity t = new TaskEntity();
        t.setId(UUID.randomUUID());
        t.setProjectId(UUID.randomUUID());
        t.setRequirementGroupId(UUID.randomUUID());
        t.setWorkspaceId(UUID.randomUUID());
        t.setCreatedBy(UUID.randomUUID());
        t.setTitle("sample");
        t.setRequirement("do something");
        return t;
    }

    private GroupContext groupContext(TaskEntity task) {
        return new GroupContext(task.getRequirementGroupId().toString(), task.getProjectId().toString(),
                "需求群", "背景", List.of("repo-1"),
                List.of(new ContextMessage(1L, "TEXT", "USER", "u-1", "补充需求")),
                List.of(new ContextSkill(UUID.randomUUID(), "编码规范")),
                List.of(new ContextMemory("缓存约定", "Redis 前缀 projectId", "architecture")));
    }

    @Test
    void assembleEnrichesRepositoryManifestWithWorkspaceMapping() {
        TaskEntity task = task();
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");
        UUID repositoryId = UUID.randomUUID();
        GroupContext context = groupContext(task);
        context.setRepositories(List.of(new ContextRepository(repositoryId.toString(), "前端仓库",
                "example/frontend", "main", null, null, null)));
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setWorkspacePath("repo-2");
        worktree.setBaseRef("develop");
        worktree.setSourceBranch("feat/task-123");
        when(workspaceRepositories.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(worktree));

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, null,
                UUID.randomUUID(), null, null, null, context);

        assertThat(input.getRepositories()).singleElement().satisfies(repository -> {
            assertThat(repository.getName()).isEqualTo("前端仓库");
            assertThat(repository.getWorkspacePath()).isEqualTo("repo-2");
            assertThat(repository.getBaseRef()).isEqualTo("develop");
            assertThat(repository.getSourceBranch()).isEqualTo("feat/task-123");
        });
    }

    @Test void assembleFillsConversationSkillsMemoriesFromSnapshot() {
        TaskEntity task = task();
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");
        GroupContext gc = groupContext(task);

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, null,
                UUID.randomUUID(), null, null, null, gc);

        assertThat(input.getConversation()).isSameAs(gc.getConversation());
        assertThat(input.getSkills()).isSameAs(gc.getSkills());
        assertThat(input.getMemories()).isSameAs(gc.getMemories());
        assertThat(input.getRequirementTitle()).isEqualTo("需求群");
        assertThat(input.getRequirementDescription()).isEqualTo("背景");
        assertThat(input.getTaskTitle()).isEqualTo(task.getTitle());
        assertThat(input.getInstruction()).isEqualTo("实现");
    }

    @Test void buildGroupContextPassesActorProjectGroupAndFixedLimit() {
        TaskEntity task = task();
        GroupContext gc = groupContext(task);
        when(contextService.buildForGroup(any(), any(), any(), any())).thenReturn(gc);

        GroupContext result = assembler.buildGroupContext(task);

        assertThat(result).isSameAs(gc);
        verify(contextService).buildForGroup(eq(task.getCreatedBy()), eq(task.getProjectId()),
                eq(task.getRequirementGroupId()), eq(50));
    }

    @Test void buildGroupContextFailureReturnsNullNotThrows() {
        TaskEntity task = task();
        when(contextService.buildForGroup(any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在"));

        assertThat(assembler.buildGroupContext(task)).isNull();
    }

    @Test void buildGroupContextUsesPersistedSnapshotInsteadOfLiveContext() {
        TaskEntity task = task();
        GroupContext snapshot = groupContext(task);
        task.setContextSnapshot(contextSnapshotCodec.encode(snapshot));

        GroupContext result = assembler.buildGroupContext(task);

        assertThat(result.getConversation()).extracting(ContextMessage::getText).containsExactly("补充需求");
        verify(contextService, org.mockito.Mockito.never()).buildForGroup(any(), any(), any(), any());
    }

    @Test void invalidPersistedSnapshotDoesNotFallBackToLaterLiveMessages() {
        TaskEntity task = task();
        task.setContextSnapshot(Map.of("version", 999));

        assertThat(assembler.buildGroupContext(task)).isNull();
        verify(contextService, org.mockito.Mockito.never()).buildForGroup(any(), any(), any(), any());
    }

    @Test void assembleWithNullSnapshotKeepsContextEmpty() {
        TaskEntity task = task();
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.REVIEWING, null,
                UUID.randomUUID(), null, null, null, null);

        assertThat(input.getConversation()).isNull();
        assertThat(input.getSkills()).isNull();
        assertThat(input.getMemories()).isNull();
        assertThat(input.getRequirementTitle()).isNull();
        assertThat(input.getRequirementDescription()).isNull();
    }

    @Test void infrastructureFeedbackUsesStableCodeAndControlledDescriptionOnly() {
        TaskEntity task = task();
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("重试");
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setFailureCode("LLM_FINISH_LENGTH");
        failure.setMessage("request failed at C:\\host\\workspace\\secret.env with token=abc");

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.PLAN, failure,
                null, null, null, null, null);

        assertThat(input.getFeedback()).contains("LLM_FINISH_LENGTH", "长度上限")
                .doesNotContain("C:\\host", "secret.env", "token=abc", failure.getMessage());
    }

    @Test void unknownInfrastructureFailureCodeIsNotReflectedIntoPrompt() {
        TaskEntity task = task();
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setFailureCode("C:\\host\\private-path");

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, failure,
                UUID.randomUUID(), null, null, null, null);

        assertThat(input.getFeedback()).contains("FAILED_INFRASTRUCTURE", "基础设施暂不可用")
                .doesNotContain("private-path", failure.getFailureCode());
    }

    @Test void continuationTaskInjectsSourceDiffSummaryAtConversationHead() {
        TaskEntity task = task();
        task.setContinuationOfTaskId(UUID.randomUUID());
        UUID batchId = UUID.randomUUID();
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId);
        batch.setProjectId(task.getProjectId());
        batch.setTaskId(task.getContinuationOfTaskId());
        when(diffBatches.selectList(any())).thenReturn(List.of(batch));
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID());
        diff.setChangeStats(Map.of("files", 1, "additions", 5, "deletions", 3));
        when(diffMapper.selectList(any())).thenReturn(List.of(diff));
        GroupContext gc = groupContext(task);
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, null,
                UUID.randomUUID(), null, null, null, gc);

        List<ContextMessage> conversation = input.getConversation();
        assertThat(conversation).hasSize(gc.getConversation().size() + 1);
        ContextMessage head = conversation.get(0);
        assertThat(head.getType()).isEqualTo("DIFF");
        assertThat(head.getSenderType()).isEqualTo("AGENT");
        assertThat(head.getText()).contains(diff.getId().toString());
        assertThat(head.getText()).contains("新增 5 行");
        assertThat(head.getText()).contains("删除 3 行");
        // 原群聊消息仍保留，紧随源 Diff 摘要之后
        assertThat(conversation.get(1).getText()).isEqualTo("补充需求");
    }

    @Test void nonContinuationTaskLeavesConversationInstanceUntouched() {
        TaskEntity task = task();
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");
        GroupContext gc = groupContext(task);

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, null,
                UUID.randomUUID(), null, null, null, gc);

        assertThat(input.getConversation()).isSameAs(gc.getConversation());
    }

    @Test void continuationTaskWithNoSourceBatchKeepsConversationUntouched() {
        TaskEntity task = task();
        task.setContinuationOfTaskId(UUID.randomUUID());
        when(diffBatches.selectList(any())).thenReturn(List.of());
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");
        GroupContext gc = groupContext(task);

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, null,
                UUID.randomUUID(), null, null, null, gc);

        assertThat(input.getConversation()).isSameAs(gc.getConversation());
    }

    @Test void continuationTaskInjectsSourceDiffEvenWhenGroupSnapshotFails() {
        TaskEntity task = task();
        task.setContinuationOfTaskId(UUID.randomUUID());
        UUID batchId = UUID.randomUUID();
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId);
        batch.setProjectId(task.getProjectId());
        batch.setTaskId(task.getContinuationOfTaskId());
        when(diffBatches.selectList(any())).thenReturn(List.of(batch));
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID());
        when(diffMapper.selectList(any())).thenReturn(List.of(diff));
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UUID.randomUUID());
        step.setInstruction("实现");

        AgentInput input = assembler.assemble(task, step, OrchestrationPhase.CODING, null,
                UUID.randomUUID(), null, null, null, null);

        // 群快照缺失时，conversation 至少包含源 Diff 摘要这一条
        assertThat(input.getConversation()).hasSize(1);
        assertThat(input.getConversation().get(0).getType()).isEqualTo("DIFF");
    }
}
