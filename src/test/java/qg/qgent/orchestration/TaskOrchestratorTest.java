package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.agent.CodingAgent;
import qg.qgent.orchestration.agent.PlanAgent;
import qg.qgent.orchestration.agent.ReviewAgent;
import qg.qgent.orchestration.agent.TestAgent;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.ExecutionResult;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.SandboxWorkerProperties;
import qg.qgent.service.EventService;
import qg.qgent.service.FinalDiffBundleService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.TaskRunService;
import qg.qgent.service.TaskService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskOrchestrator 主链路 Mockito 测试：驱动整条 PLAN→CODING→TESTING→REVIEWING 循环，
 * 断言 Task 终态、Plan 步骤落库、TaskRun 角色链与重试/质量循环的反馈装配。
 * 纯单元测试：全部依赖 Mock，不启动 Spring 上下文、不访问 DB。
 */
class TaskOrchestratorTest {
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final TaskStepMapper stepMapper = mock(TaskStepMapper.class);
    private final StepScheduler stepScheduler = mock(StepScheduler.class);
    private final WorkspaceRepositoryMapper repoMapper = mock(WorkspaceRepositoryMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TaskRunService taskRunService = mock(TaskRunService.class);
    private final EventService eventService = mock(EventService.class);
    private final FinalDiffBundleService finalDiffBundles = mock(FinalDiffBundleService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final List<UUID> createdRunIds = new ArrayList<>();
    private final AgentContextAssembler contextAssembler = new AgentContextAssembler();
    private final GitHubAppClient githubAppClient = mock(GitHubAppClient.class);
    private final SandboxSessionManager sessionManager = new SandboxSessionManager(
            mock(SandboxWorkerClient.class), new SandboxWorkerProperties(), mock(WorkspaceMapper.class), repoMapper,
            mock(ProjectRepositoryMapper.class), mock(qg.qgent.mapper.GitHubRepositoryMapper.class),
            mock(qg.qgent.mapper.GitHubInstallationMapper.class), mock(qg.qgent.service.GitCredentialService.class), githubAppClient);

    private TaskOrchestrator orchestrator(AgentRunExecutor executor) {
        return new TaskOrchestrator(new OrchestrationStateMachine(), stepScheduler, executor, contextAssembler,
                taskService, taskRunService, taskMapper, stepMapper, repoMapper, eventService,
                notificationService, sessionManager, finalDiffBundles);
    }

    /** Maven 文件树 + Mock LLM：Plan 两轮、Coding 一次 finalResult、Test 一次分析、Review 一次 finalResult，全部成功。 */
    private TaskOrchestrator realAgentOrchestrator() {
        LlmClient llm = mock(LlmClient.class);
        WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
        WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
        ExecutionPort executionPort = mock(ExecutionPort.class);
        WorkspaceDiffAccess diffAccess = mock(WorkspaceDiffAccess.class);
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 0, "BUILD SUCCESS", "", null));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff --git a/X.java b/X.java", "base", "head"));
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}",
                        """
                        {
                          "taskUnderstanding": "understand the task",
                          "implementationGoals": ["implement"],
                          "steps": [{"title":"impl","files":["src/main/java/X.java"],"description":"do it"}],
                          "testPlan": "run tests",
                          "risks": ["risk"]
                        }
                        """);
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"implemented\"," +
                                "\"modifiedFiles\":[\"src/main/java/X.java\"]}}",
                        "{\"success\":true,\"summary\":\"tests passed\",\"failures\":[],\"needsCodingFix\":false}",
                        "{\"finalResult\":{\"success\":true,\"summary\":\"review passed\",\"findings\":[]," +
                                "\"suggestions\":[],\"needsCodingFix\":false}}");
        return orchestrator(new AgentRunExecutor(new PlanAgent(llm, codeAccess),
                new CodingAgent(llm, codeAccess, writer), new TestAgent(llm, codeAccess, executionPort),
                new ReviewAgent(llm, codeAccess, diffAccess)));
    }

    private TaskEntity task(UUID projectId, UUID taskId) {
        TaskEntity t = new TaskEntity();
        t.setId(taskId);
        t.setProjectId(projectId);
        t.setRequirementGroupId(UUID.randomUUID());
        t.setWorkspaceId(UUID.randomUUID());
        t.setTitle("sample task");
        t.setRequirement("do something");
        t.setStatus("PLANNING");
        t.setDeliveryMode("DIFF_FIRST");
        t.setCreatedBy(UUID.randomUUID());
        when(taskMapper.claimForOrchestration(projectId, taskId)).thenReturn(1);
        return t;
    }

    private TaskStepEntity step(UUID taskId, String role) {
        TaskStepEntity s = new TaskStepEntity();
        s.setId(UUID.randomUUID());
        s.setTaskId(taskId);
        s.setRole(role);
        s.setInstruction("execute " + role);
        s.setStatus("PENDING");
        return s;
    }

    private void stubStepScheduler(UUID taskId) {
        when(stepScheduler.findStepForPhase(eq(taskId), any())).thenAnswer(inv ->
                step(taskId, ((OrchestrationPhase) inv.getArgument(1)).role()));
    }

    private void stubRunCreation(UUID projectId, UUID taskId) {
        when(taskRunService.createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TaskRunEntity run = new TaskRunEntity();
                    run.setId(UUID.randomUUID());
                    createdRunIds.add(run.getId());
                    return run;
                });
    }

    private void stubPlanPersistence(TaskEntity task) {
        WorkspaceRepositoryEntity repo = new WorkspaceRepositoryEntity();
        repo.setProjectRepositoryId(UUID.randomUUID());
        when(repoMapper.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repo));
    }

    private AgentRunOutcome outcome(OrchestrationPhase phase, RunOutcome result) {
        AgentRunOutcome o = new AgentRunOutcome();
        o.setPhase(phase);
        o.setOutcome(result);
        o.setMessage("outcome:" + result);
        return o;
    }

    private AgentRunOutcome planSuccess() {
        PlanResult plan = new PlanResult();
        plan.setObjectives(List.of("objective"));
        PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
        step.setTitle("impl");
        step.setFiles(List.of("src/main/java/..."));
        plan.setImplementationSteps(List.of(step));
        AgentRunOutcome o = outcome(OrchestrationPhase.PLAN, RunOutcome.SUCCEEDED);
        o.setPlanResult(plan);
        return o;
    }

    private AgentRunExecutor runSequence(TaskEntity task, List<AgentRunOutcome> sequence) {
        AtomicInteger idx = new AtomicInteger();
        AgentRunExecutor executor = mock(AgentRunExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(inv -> sequence.get(idx.getAndIncrement()));
        orchestrator(executor).orchestrate(task.getProjectId(), task.getId());
        return executor;
    }

    private void assertTerminalStatus(String expected) {
        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(taskMapper, atLeast(1)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus()).isEqualTo(expected);
    }

    @Test void happyPathCompletesSuccessWithPlanStepsAndThreeRuns() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        realAgentOrchestrator().orchestrate(projectId, taskId);

        assertTerminalStatus("SUCCEEDED");
        verify(taskService).addSteps(eq(projectId), eq(taskId), eq(task.getCreatedBy()),
                argThat(requests -> requests.size() == 3));

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskRunService, times(3)).createForStep(eq(projectId), eq(taskId), any(), roleCaptor.capture(),
                any(), any(), any());
        assertThat(roleCaptor.getAllValues()).containsExactly("DEVELOPER", "TESTER", "REVIEWER");
        verify(taskRunService, times(3)).complete(any(), anyString());
    }

    @Test void testFailureRequeuesCodingWithFeedbackThenSucceeds() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        AgentRunExecutor executor = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("SUCCEEDED");
        verify(taskRunService, times(5)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any());
        verify(taskRunService, times(5)).complete(any(), anyString());
        verify(executor, times(6)).execute(any(), any());
    }

    @Test void testFailureFeedsFeedbackIntoRetryCodingInput() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        AgentRunExecutor executor = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(executor, times(6)).execute(any(), inputCaptor.capture());
        List<AgentInput> codingInputs = inputCaptor.getAllValues().stream()
                .filter(in -> in.getPhase() == OrchestrationPhase.CODING).toList();
        assertThat(codingInputs).hasSize(2);
        assertThat(codingInputs.get(0).getFeedback()).isNull();
        assertThat(codingInputs.get(1).getFeedback()).isNotNull();
    }

    /** §七：TestResult 的失败项必须进入重试 Coding Agent 的输入（feedback）。 */
    @Test void testFailuresFeedIntoRetryCodingInput() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        TestResult failing = new TestResult();
        failing.setSuccess(false);
        failing.setSummary("2 tests failed");
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName("CalculatorTest.addShouldSum");
        failure.setReason("expected 5 but got 4");
        failing.setFailures(List.of(failure));

        AgentRunOutcome testingFailed = outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        testingFailed.setTestResult(failing);

        AgentRunExecutor executor = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                testingFailed,
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("SUCCEEDED");

        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(executor, times(6)).execute(any(), inputCaptor.capture());
        List<AgentInput> codingInputs = inputCaptor.getAllValues().stream()
                .filter(in -> in.getPhase() == OrchestrationPhase.CODING).toList();
        assertThat(codingInputs).hasSize(2);
        assertThat(codingInputs.get(1).getFeedback())
                .contains("CalculatorTest.addShouldSum")
                .contains("expected 5 but got 4");
    }

    /** §七：Review 失败回到 Coding→Test→Review 后成功。 */
    @Test void reviewFailureRequeuesCodingThenSucceeds() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        AgentRunOutcome reviewFailed = outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        reviewFailed.setReviewResult(reviewWithFindings());

        AgentRunExecutor executor = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                reviewFailed,
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("SUCCEEDED");
        verify(taskRunService, times(6)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any());
        verify(taskRunService, times(6)).complete(any(), anyString());
        verify(executor, times(7)).execute(any(), any());
    }

    /** §七：ReviewResult.findings/suggestions 必须进入重试 Coding Agent 的输入（feedback）。 */
    @Test void reviewFindingsFeedIntoRetryCodingInput() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        AgentRunOutcome reviewFailed = outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        reviewFailed.setReviewResult(reviewWithFindings());

        AgentRunExecutor executor = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                reviewFailed,
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("SUCCEEDED");

        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(executor, times(7)).execute(any(), inputCaptor.capture());
        List<AgentInput> codingInputs = inputCaptor.getAllValues().stream()
                .filter(in -> in.getPhase() == OrchestrationPhase.CODING).toList();
        assertThat(codingInputs).hasSize(2);
        assertThat(codingInputs.get(0).getFeedback()).isNull();
        assertThat(codingInputs.get(1).getFeedback())
                .contains("null check missing")
                .contains("add null check");
    }

    private ReviewResult reviewWithFindings() {
        ReviewResult review = new ReviewResult();
        review.setSuccess(false);
        review.setSummary("major issue found");
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile("src/main/java/X.java");
        finding.setIssue("null check missing");
        finding.setSuggestion("add null check");
        review.setFindings(List.of(finding));
        review.setSuggestions(List.of("add null check"));
        review.setNeedsCodingFix(true);
        return review;
    }

    @Test void maxQualityFixLoopsExhaustedFailsTask() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY)));

        assertTerminalStatus("FAILED");
    }

    @Test void infraFailureRetriesSamePhaseThenSucceeds() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.FAILED_INFRASTRUCTURE),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("SUCCEEDED");

        ArgumentCaptor<UUID> retryCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskRunService, times(4)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(),
                retryCaptor.capture());
        assertThat(retryCaptor.getAllValues().get(0)).isNull();
        assertThat(retryCaptor.getAllValues().get(1)).isNotNull();
    }

    @Test void reviewSuccessCreatesDiffBatchFromLatestSuccessfulCodingRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        verify(finalDiffBundles).createPendingBatch(projectId, taskId, createdRunIds.get(2));
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void diffBundleFailureFailsTaskInsteadOfLeavingItRunning() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);
        when(finalDiffBundles.createPendingBatch(eq(projectId), eq(taskId), any()))
                .thenThrow(new IllegalStateException("snapshot failed"));

        assertThatThrownBy(() -> runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED))))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("snapshot failed");

        assertTerminalStatus("FAILED");
    }

    @Test void mrFirstReviewSuccessKeepsExistingSucceededTerminalState() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setDeliveryMode("MR_FIRST");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("SUCCEEDED");
        verify(finalDiffBundles, never()).createPendingBatch(any(), any(), any());
    }

    @Test void concurrentOrchestrationIsRejectedWhenPersistentClaimIsLost() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(taskMapper.claimForOrchestration(projectId, taskId)).thenReturn(0);
        AgentRunExecutor executor = mock(AgentRunExecutor.class);

        assertThatThrownBy(() -> orchestrator(executor).orchestrate(projectId, taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already being orchestrated");

        verify(executor, never()).execute(any(), any());
    }

    @Test void cancelledCompletesCancelled() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.CANCELLED)));

        assertTerminalStatus("CANCELLED");
    }

    @Test void planFailureFailsTaskWithoutAnyRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);

        runSequence(task, List.of(outcome(OrchestrationPhase.PLAN, RunOutcome.FAILED)));

        assertTerminalStatus("FAILED");
        verify(taskRunService, never()).createForStep(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void notStartableTaskRejected() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setStatus("SUCCEEDED");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        assertThatThrownBy(() -> realAgentOrchestrator().orchestrate(projectId, taskId))
                .isInstanceOf(IllegalStateException.class);
        verify(taskRunService, never()).createForStep(any(), any(), any(), any(), any(), any(), any());
    }
}
