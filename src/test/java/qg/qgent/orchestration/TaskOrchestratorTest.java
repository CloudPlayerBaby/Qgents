package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.dto.GroupContext;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.service.EventService;
import qg.qgent.service.FinalDiffBundleService;
import qg.qgent.service.MessageService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.OrchestratorAgentService;
import qg.qgent.service.TaskExecutionArtifactService;
import qg.qgent.service.TaskPlanMaterializationService;
import qg.qgent.service.TaskRunService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Planner bootstrap 编排测试。PLAN 只产生 Task 级产物，正式图只消费已物化并冻结的执行步骤。
 */
class TaskOrchestratorTest {

    @Test
    void plannerCreatesNoTaskRunAndFormalGraphStartsAtDeveloper() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        verify(fixture.taskRuns, times(3)).createForStep(eq(task.getProjectId()), eq(task.getId()), any(),
                anyString(), any(), any(), any());
        verify(fixture.taskRuns, never()).createForStep(eq(task.getProjectId()), eq(task.getId()), eq(planner.getId()),
                anyString(), any(), any(), any());
        verify(fixture.materialization).materialize(eq(task), any(PlanResult.class));
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("CODING"), any());
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("TESTING"), any());
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("REVIEWING"), any());
    }

    @Test
    void sendingDiffCardPostsQuotableDiffMessage() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        UUID batchId = UUID.randomUUID();
        when(fixture.diffs.createPendingBatch(any(), any(), any())).thenReturn(batchId);
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID());
        diff.setChangeStats(Map.of("files", 1, "additions", 5, "deletions", 3));
        when(fixture.diffMapper.selectList(any())).thenReturn(List.of(diff));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        ArgumentCaptor<MessageSendRequest> captor = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(fixture.messages, atLeastOnce()).sendAsAgent(eq(task.getRequirementGroupId()), any(), captor.capture());
        List<MessageSendRequest> diffRequests = captor.getAllValues().stream()
                .filter(request -> "DIFF".equals(request.getType()))
                .toList();
        assertThat(diffRequests).hasSize(1);
        MessageSendRequest diffRequest = diffRequests.get(0);
        assertThat(diffRequest.getContent().get("diffId")).isEqualTo(diff.getId().toString());
        assertThat(diffRequest.getContent().get("title")).isEqualTo(task.getTitle());
        assertThat(diffRequest.getContent().get("additions")).isEqualTo(5);
        assertThat(diffRequest.getContent().get("deletions")).isEqualTo(3);
    }

    @Test
    void qualityFailureRequeuesOnlyLastDeveloperStep() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developerOne = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity developerTwo = fixture.step(task, "DEVELOPER", 3);
        TaskStepEntity tester = fixture.step(task, "TESTER", 4);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 5);
        List<TaskStepEntity> all = List.of(planner, developerOne, developerTwo, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.CODING), fixture.outcome(OrchestrationPhase.TESTING,
                        RunOutcome.FAILED_QUALITY), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developerOne.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developerTwo.getId()), anyString(), any(), any(), any());
    }

    @Test
    void failedPlannerDoesNotEnterFormalGraphOrCreateTaskRun() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        fixture.stubPlan(task, planner, List.of(planner));

        fixture.orchestrator(fixture.sequenceAgent(fixture.outcome(OrchestrationPhase.PLAN, RunOutcome.FAILED)))
                .orchestrate(task.getProjectId(), task.getId());

        verify(fixture.materialization, never()).materialize(any(), any());
        verifyNoInteractions(fixture.taskRuns);
        assertThat(fixture.updatedStatuses()).contains("FAILED");
    }

    @Test
    void plannerMaterializationFailureTerminatesBeforeAnyFormalTaskRun() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        fixture.stubPlan(task, planner, List.of(planner));
        doThrow(new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "PLAN_STEP_AGENT_UNMATCHED", "no suitable developer agent"))
                .when(fixture.materialization).materialize(eq(task), any(PlanResult.class));

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess()))
                .orchestrate(task.getProjectId(), task.getId());

        verifyNoInteractions(fixture.taskRuns);
        assertThat(planner.getStatus()).isEqualTo("FAILED");
        verify(fixture.steps, times(2)).updateById(planner);
        assertThat(fixture.updatedStatuses()).contains("FAILED");
    }

    @Test
    void plannerInfrastructureFailureRetriesThenContinuesToFormalGraph() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        Agent agent = fixture.sequenceAgent(fixture.outcome(OrchestrationPhase.PLAN, RunOutcome.FAILED_INFRASTRUCTURE),
                fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        verify(fixture.materialization).materialize(eq(task), any(PlanResult.class));
        verify(fixture.taskRuns, times(3)).createForStep(eq(task.getProjectId()), eq(task.getId()), any(),
                anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
    }

    @Test
    void mrFirstTaskEntersDeliveringWithoutDiffConfirmation() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.updatedStatuses()).contains("DELIVERING");
        // MR_FIRST 走系统授权批次：不创建待确认批次，交付意图（含 delivery.started 事件）
        // 由 FinalDiffBundleService.createSystemAcceptedBatch 在短事务内原子落库
        verify(fixture.diffs, never()).createPendingBatch(any(), any(), any());
        verify(fixture.diffs).createSystemAcceptedBatch(eq(task.getProjectId()), eq(task.getId()), any());
        // SYSTEM 批次是内部交付事实，MR_FIRST 不得借它发送需要用户确认的 DIFF 卡片。
        verify(fixture.diffMapper, never()).selectList(any());
    }

    @Test
    void emptyFinalDiffCompletesWithNoCodeChangesCardAndSkippedEvent() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        when(fixture.diffs.createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any()))
                .thenThrow(new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "FINAL_DIFF_EMPTY", "no uncommitted changes"));

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        verify(fixture.diffs).createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any());
        verify(fixture.diffMapper, never()).selectList(any());
        ArgumentCaptor<Map> eventPayload = ArgumentCaptor.forClass(Map.class);
        verify(fixture.events).publish(eq(task.getProjectId()), eq(task.getRequirementGroupId()),
                eq("diff-review.skipped"), eq(task.getId().toString()), eventPayload.capture());
        assertThat(eventPayload.getValue()).containsEntry("projectId", task.getProjectId())
                .containsEntry("taskId", task.getId()).containsEntry("reason", "FINAL_DIFF_EMPTY");
        verify(fixture.events, never()).publish(any(), any(), eq("diff.created"), any(), any());
        verify(fixture.events, never()).publish(any(), any(), eq("diff-review.created"), any(), any());
        verify(fixture.events, never()).publish(any(), any(), eq("delivery.failed"), any(), any());
        verify(fixture.events, never()).publish(any(), any(), eq("delivery.completed"), any(), any());

        ArgumentCaptor<MessageSendRequest> cards = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(fixture.messages, atLeastOnce()).sendAsAgent(eq(task.getRequirementGroupId()), any(), cards.capture());
        MessageSendRequest completed = cards.getAllValues().stream()
                .filter(card -> "TASK_STATUS".equals(card.getType()))
                .filter(card -> "SUCCEEDED".equals(card.getContent().get("status")))
                .filter(card -> !card.getContent().containsKey("node"))
                .findFirst().orElseThrow();
        assertThat(completed.getContent().get("message"))
                .isEqualTo("任务已完成，但未检测到代码变更，因此没有生成 Diff 或 MR。");
        verify(fixture.notifications).notify(eq(task.getCreatedBy()), eq(task.getProjectId()),
                eq(task.getRequirementGroupId()), eq("TASK_COMPLETED"), any(), any(), eq(task.getId().toString()));
        verify(fixture.notifications, never()).notify(any(), any(), any(), eq("TASK_FAILED"), any(), any(), any());
    }

    @Test
    void planCompleteButConcurrentClaimFailsSkipsFormalExecution() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        // 规划完成后统一原子认领失败：并发另一执行器已物化并认领 / 用户已取消等，返回 0 放弃执行
        when(fixture.tasks.claimForOrchestration(any(), any())).thenReturn(0);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess()))
                .orchestrate(task.getProjectId(), task.getId());

        verify(fixture.materialization).materialize(eq(task), any(PlanResult.class));
        // 认领失败后不再进入正式图：不创建任何 TaskRun，也不触碰运行产物
        verifyNoInteractions(fixture.taskRuns);
        verify(fixture.artifacts, never()).createRunArtifact(any(), any(), any(), any(), any());
    }

    @Test
    void reviewerArtifactSummaryIncludesStructuredFindings() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        AgentRunOutcome review = fixture.success(OrchestrationPhase.REVIEWING);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(true);
        reviewResult.setSummary("looks good");
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile("src/App.java");
        finding.setLine(12);
        finding.setIssue("missing null check");
        finding.setSuggestion("add null check");
        reviewResult.setFindings(List.of(finding));
        review.setReviewResult(reviewResult);
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), review);

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        ArgumentCaptor<Map> summary = ArgumentCaptor.forClass(Map.class);
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("REVIEWING"), summary.capture());
        Map reviewSummary = (Map) summary.getValue().get("review");
        assertThat(reviewSummary).containsEntry("success", true);
        assertThat((List<?>) reviewSummary.get("findings")).hasSize(1);
        assertThat((Map<String, Integer>) reviewSummary.get("severityCount")).containsEntry("MAJOR", 1);
    }

    private static final class Fixture {
        private final TaskMapper tasks = mock(TaskMapper.class);
        private final TaskStepMapper steps = mock(TaskStepMapper.class);
        private final TaskRunService taskRuns = mock(TaskRunService.class);
        private final TaskPlanMaterializationService materialization = mock(TaskPlanMaterializationService.class);
        private final AgentRegistry registry = mock(AgentRegistry.class);
        private final AgentContextAssembler context = mock(AgentContextAssembler.class);
        private final FinalDiffBundleService diffs = mock(FinalDiffBundleService.class);
        private final EventService events = mock(EventService.class);
        private final TaskExecutionArtifactService artifacts = mock(TaskExecutionArtifactService.class);
        private final DiffMapper diffMapper = mock(DiffMapper.class);
        private final MessageService messages = mock(MessageService.class);
        private final NotificationService notifications = mock(NotificationService.class);
        private final OrchestratorAgentService orchestratorAgents = mock(OrchestratorAgentService.class);
        private final ThreadLocal<Agent> currentAgent = new ThreadLocal<>();

        TaskEntity task() {
            TaskEntity task = new TaskEntity();
            task.setId(UUID.randomUUID());
            task.setProjectId(UUID.randomUUID());
            task.setRequirementGroupId(UUID.randomUUID());
            task.setWorkspaceId(UUID.randomUUID());
            task.setTitle("task");
            task.setRequirement("requirement");
            task.setCreatedBy(UUID.randomUUID());
            task.setStatus("PLANNING");
            when(tasks.selectById(task.getId())).thenReturn(task);
            when(tasks.claimForOrchestration(any(), any())).thenReturn(1);
            when(tasks.claimForResume(any(), any())).thenReturn(1);
            when(diffs.createPendingBatch(any(), any(), any())).thenReturn(UUID.randomUUID());
            when(diffs.createSystemAcceptedBatch(any(), any(), any())).thenReturn(UUID.randomUUID());
            when(orchestratorAgents.resolveIdForTask(task)).thenReturn(UUID.randomUUID());
            return task;
        }

        TaskStepEntity step(TaskEntity task, String role, int sequence) {
            TaskStepEntity step = new TaskStepEntity();
            step.setId(UUID.randomUUID());
            step.setTaskId(task.getId());
            step.setRole(role);
            step.setSequenceNo(sequence);
            step.setInstruction("instruction");
            step.setStatus("PENDING");
            return step;
        }

        void stubPlan(TaskEntity task, TaskStepEntity planner, List<TaskStepEntity> all) {
            when(materialization.ensurePlannerStep(task)).thenReturn(planner);
            when(materialization.materialize(eq(task), any())).thenReturn(all);
            when(steps.selectList(any())).thenReturn(all);
            when(steps.selectById(any())).thenAnswer(invocation -> all.stream()
                    .filter(step -> step.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
            when(registry.resolve(any(), any())).thenAnswer(invocation -> Optional.of(currentAgent.get()));
            when(context.buildGroupContext(task)).thenReturn(mock(GroupContext.class));
            when(taskRuns.createForStep(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                TaskRunEntity run = new TaskRunEntity();
                run.setId(UUID.randomUUID());
                return run;
            });
        }

        TaskOrchestrator orchestrator(Agent agent) {
            currentAgent.set(agent);
            return new TaskOrchestrator(new OrchestrationStateMachine(), new WorkflowGraphBuilder(), registry, context,
                    taskRuns, tasks, steps, events,
                    notifications, mock(SandboxSessionManager.class), artifacts, diffs,
                    diffMapper, messages, orchestratorAgents,
                    materialization);
        }

        Agent sequenceAgent(AgentRunOutcome... outcomes) {
            AtomicInteger index = new AtomicInteger();
            return input -> outcomes[index.getAndIncrement()];
        }

        AgentRunOutcome success(OrchestrationPhase phase) { return outcome(phase, RunOutcome.SUCCEEDED); }

        AgentRunOutcome outcome(OrchestrationPhase phase, RunOutcome value) {
            AgentRunOutcome result = new AgentRunOutcome();
            result.setPhase(phase);
            result.setOutcome(value);
            result.setMessage(value.name());
            return result;
        }

        AgentRunOutcome planSuccess() {
            PlanResult plan = new PlanResult();
            plan.setObjectives(List.of("goal"));
            PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
            step.setTitle("implement");
            step.setFiles(List.of("src/App.java"));
            plan.setImplementationSteps(List.of(step));
            AgentRunOutcome result = success(OrchestrationPhase.PLAN);
            result.setPlanResult(plan);
            return result;
        }

        List<String> updatedStatuses() {
            return mockingDetails(tasks).getInvocations().stream().filter(invocation -> invocation.getMethod().getName()
                    .equals("updateById")).map(invocation -> ((TaskEntity) invocation.getArgument(0)).getStatus()).toList();
        }
    }
}
