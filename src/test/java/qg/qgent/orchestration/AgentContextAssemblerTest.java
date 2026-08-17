package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextSkill;
import qg.qgent.dto.GroupContext;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.service.ContextService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentContextAssembler 上下文接线测试：确认 ContextService 快照（群聊/Skill/Memory）被填充进
 * AgentInput、limit 恒为 50、组装失败仅返回 null 不抛出。
 */
class AgentContextAssemblerTest {

    private final ContextService contextService = mock(ContextService.class);
    private final AgentContextAssembler assembler = new AgentContextAssembler(contextService);

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
                List.of(new ContextSkill("编码规范", "禁止提交 .env")),
                List.of(new ContextMemory("缓存约定", "Redis 前缀 projectId", "architecture")));
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
}
