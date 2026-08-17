package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 AI 编排冒烟测试。仅在显式设置 {@code QGENTS_AI_E2E_ENABLED=true} 时启动，
 * 使用环境变量指定的专用 Project、需求群、Workspace 和测试用户，真实调用 LLM、Worker 与 MySQL。
 *
 * <p>前置条件：Workspace 必须是专门的可写测试工作区；{@code QGENTS_AI_E2E_REQUIREMENT}
 * 必须描述可验证、可回收的修改。测试会保留 Task 及其产物作为审计记录，工作区改动由测试环境
 * 管理员按 Diff-first 流程确认或丢弃，绝不能指向日常开发 Workspace。</p>
 */
@Tag("ai-e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "QGENTS_AI_E2E_ENABLED", matches = "true")
class AiFullChainIntegrationTest {

    @Autowired
    private TaskOrchestrator orchestrator;
    @Autowired
    private TaskMapper tasks;
    @Autowired
    private TaskStepMapper steps;
    @Autowired
    private TaskRunMapper runs;
    @Autowired
    private TaskExecutionArtifactMapper artifacts;
    @Autowired
    private DiffReviewBatchMapper diffBatches;

    @Test
    void realPlannerDeveloperTesterReviewerChainCreatesAuditableDiffBatch() {
        UUID projectId = requiredUuid("QGENTS_AI_E2E_PROJECT_ID");
        UUID groupId = requiredUuid("QGENTS_AI_E2E_REQUIREMENT_GROUP_ID");
        UUID workspaceId = requiredUuid("QGENTS_AI_E2E_WORKSPACE_ID");
        UUID actorId = requiredUuid("QGENTS_AI_E2E_ACTOR_ID");
        String requirement = required("QGENTS_AI_E2E_REQUIREMENT");
        TaskEntity task = createTask(projectId, groupId, workspaceId, actorId, requirement);
        tasks.insert(task);

        orchestrator.orchestrate(projectId, task.getId());

        TaskEntity completed = tasks.selectById(task.getId());
        assertThat(completed.getPlanMaterializedAt()).as("Planner result must be frozen before formal execution")
                .isNotNull();
        assertThat(completed.getStatus()).isEqualTo("WAITING_DIFF_CONFIRMATION");

        List<TaskStepEntity> materialized = steps.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, task.getId()).orderByAsc(TaskStepEntity::getSequenceNo));
        assertThat(materialized).extracting(TaskStepEntity::getRole)
                .contains("PLANNER", "DEVELOPER", "TESTER", "REVIEWER");
        assertThat(materialized).allMatch(step -> "SUCCEEDED".equals(step.getStatus()));

        List<TaskRunEntity> taskRuns = runs.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getTaskId, task.getId()));
        assertThat(taskRuns).isNotEmpty();
        assertThat(taskRuns).noneMatch(run -> "PLANNER".equals(run.getRole()));
        assertThat(taskRuns).allMatch(run -> "SUCCEEDED".equals(run.getStatus()));

        List<TaskExecutionArtifactEntity> taskArtifacts = artifacts.selectList(
                Wrappers.<TaskExecutionArtifactEntity>lambdaQuery().eq(TaskExecutionArtifactEntity::getTaskId, task.getId()));
        assertThat(taskArtifacts).anyMatch(artifact -> "PLAN".equals(artifact.getArtifactType())
                && artifact.getTaskRunId() == null && artifact.getTaskStepId() == null);
        assertThat(taskArtifacts).anyMatch(artifact -> "CODING".equals(artifact.getArtifactType())
                && artifact.getTaskRunId() != null && artifact.getTaskStepId() != null);
        assertThat(taskArtifacts).anyMatch(artifact -> "TESTING".equals(artifact.getArtifactType())
                && artifact.getTaskRunId() != null && artifact.getTaskStepId() != null);
        assertThat(taskArtifacts).anyMatch(artifact -> "REVIEWING".equals(artifact.getArtifactType())
                && artifact.getTaskRunId() != null && artifact.getTaskStepId() != null);

        DiffReviewBatchEntity batch = diffBatches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, task.getId()));
        assertThat(batch).isNotNull();
        assertThat(batch.getReviewStatus()).isEqualTo("PENDING_CONFIRMATION");
    }

    private TaskEntity createTask(UUID projectId, UUID groupId, UUID workspaceId, UUID actorId, String requirement) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TaskEntity task = new TaskEntity();
        task.setId(UuidV7.next());
        task.setProjectId(projectId);
        task.setRequirementGroupId(groupId);
        task.setWorkspaceId(workspaceId);
        task.setTitle("AI E2E " + task.getId());
        task.setDisplayCode("E2E-" + task.getId());
        task.setRequirement(requirement);
        task.setStatus("PLANNING");
        task.setDeliveryMode("DIFF_FIRST");
        task.setCreatedBy(actorId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private UUID requiredUuid(String name) {
        try {
            return UUID.fromString(required(name));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be a UUID", exception);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required E2E environment variable: " + name);
        }
        return value;
    }
}
