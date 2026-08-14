package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import qg.qgent.api.ApiException;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.ProjectMapper;
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
import qg.qgent.service.MessageService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.TaskRunService;
import qg.qgent.service.TaskService;
import qg.qgent.service.TaskExecutionArtifactService;
import qg.qgent.service.FinalDiffBundleService;

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
    private final TaskExecutionArtifactService artifactService = mock(TaskExecutionArtifactService.class);
    private final FinalDiffBundleService finalDiffBundles = mock(FinalDiffBundleService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private static final UUID AGENT_ID = UUID.randomUUID();
    private final AgentContextAssembler contextAssembler = new AgentContextAssembler();
    private final GitHubAppClient githubAppClient = mock(GitHubAppClient.class);
    private final SandboxSessionManager sessionManager = new SandboxSessionManager(
            mock(SandboxWorkerClient.class), new SandboxWorkerProperties(), mock(WorkspaceMapper.class), repoMapper,
            mock(ProjectRepositoryMapper.class), mock(qg.qgent.mapper.GitHubRepositoryMapper.class),
            mock(qg.qgent.mapper.GitHubInstallationMapper.class), mock(qg.qgent.service.GitCredentialService.class), githubAppClient);

    private TaskOrchestrator orchestrator(AgentRunExecutor executor) {
        when(finalDiffBundles.createPendingBatch(any(), any(), any())).thenReturn(UUID.randomUUID());
        return new TaskOrchestrator(new OrchestrationStateMachine(), stepScheduler, executor, contextAssembler,
                taskService, taskRunService, taskMapper, stepMapper, repoMapper, eventService,
                mock(NotificationService.class), sessionManager, artifactService, finalDiffBundles, messageService,
                agentMapper, projectMapper);
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
        t.setCreatedBy(UUID.randomUUID());
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
        when(stepScheduler.findStepForPhase(eq(taskId), any())).thenAnswer(inv -> {
            TaskStepEntity s = step(taskId, ((OrchestrationPhase) inv.getArgument(1)).role());
            s.setAssignedAgentId(AGENT_ID);
            return s;
        });
    }

    private void stubRunCreation(UUID projectId, UUID taskId) {
        when(taskRunService.createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TaskRunEntity run = new TaskRunEntity();
                    run.setId(UUID.randomUUID());
                    return run;
                });
    }

    private void stubPlanPersistence(TaskEntity task) {
        WorkspaceRepositoryEntity repo = new WorkspaceRepositoryEntity();
        repo.setProjectRepositoryId(UUID.randomUUID());
        when(repoMapper.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repo));
        stubAgents(task);
    }

    /** 方案 A：团队内存在与角色匹配的 ACTIVE TEAM Agent，供 persistPlanSteps 分配与回群断言。 */
    private void stubAgents(TaskEntity task) {
        ProjectEntity project = new ProjectEntity();
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(eq(task.getProjectId()))).thenReturn(project);
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setRole("DEVELOPER");
        agent.setStatus("ACTIVE");
        agent.setVisibility("TEAM");
        when(agentMapper.selectList(any())).thenReturn(List.of(agent));
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

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        verify(taskService).addSteps(eq(projectId), eq(taskId), eq(task.getCreatedBy()),
                argThat(requests -> requests.size() == 3));
        verify(taskService).addSteps(eq(projectId), eq(taskId), eq(task.getCreatedBy()),
                argThat(requests -> requests.stream().allMatch(r -> AGENT_ID.equals(r.getAssignedAgentId()))));

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> agentCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskRunService, times(3)).createForStep(eq(projectId), eq(taskId), any(), roleCaptor.capture(),
                agentCaptor.capture(), any(), any());
        assertThat(roleCaptor.getAllValues()).containsExactly("DEVELOPER", "TESTER", "REVIEWER");
        // 方案 A：TaskRun.agentId 来自步骤分配的真实 Agent，不再恒 null
        assertThat(agentCaptor.getAllValues()).allSatisfy(agentId -> assertThat(agentId).isEqualTo(AGENT_ID));
        verify(taskRunService, times(3)).complete(any(), anyString());

        // sendAsAgent 接线：step 卡片 + 任务结果卡片均为 AGENT 身份、TASK_STATUS 类型、幂等键前缀正确
        ArgumentCaptor<MessageSendRequest> reqCaptor = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(messageService, atLeast(1)).sendAsAgent(eq(task.getRequirementGroupId()), eq(AGENT_ID),
                reqCaptor.capture());
        assertThat(reqCaptor.getAllValues()).allSatisfy(req -> assertThat(req.getType()).isEqualTo("TASK_STATUS"));
        assertThat(reqCaptor.getAllValues()).anyMatch(req -> req.getClientMessageId().startsWith("agent-step-"));
        assertThat(reqCaptor.getAllValues()).anyMatch(req -> req.getClientMessageId().startsWith("agent-task-"));
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

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
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

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");

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

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
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

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");

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

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");

        ArgumentCaptor<UUID> retryCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskRunService, times(4)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(),
                retryCaptor.capture());
        assertThat(retryCaptor.getAllValues().get(0)).isNull();
        assertThat(retryCaptor.getAllValues().get(1)).isNotNull();
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

    private List<AgentRunOutcome> fullSuccessSequence() {
        return List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED));
    }

    private AgentRunExecutor sequenceExecutor(List<AgentRunOutcome> sequence) {
        AgentRunExecutor executor = mock(AgentRunExecutor.class);
        AtomicInteger idx = new AtomicInteger();
        when(executor.execute(any(), any())).thenAnswer(inv -> sequence.get(idx.getAndIncrement()));
        return executor;
    }

    /** 后端3 决策：无未提交改动（FINAL_DIFF_EMPTY）视为成功降级 SUCCEEDED，且发布 diff-review.skipped 事件作为依据。 */
    @Test void finalDiffEmptyDegradesToSucceededWithSkippedEvent() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        TaskOrchestrator orchestrator = orchestrator(sequenceExecutor(fullSuccessSequence()));
        when(finalDiffBundles.createPendingBatch(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "FINAL_DIFF_EMPTY", "No uncommitted changes"));

        orchestrator.orchestrate(projectId, taskId);

        assertTerminalStatus("SUCCEEDED");
        verify(eventService).publish(eq(task.getProjectId()), eq(task.getRequirementGroupId()),
                eq("diff-review.skipped"), eq(task.getId().toString()),
                argThat(payload -> "FINAL_DIFF_EMPTY".equals(payload.get("reason"))));
    }

    /** 后端3 决策：Worker 不可用等临时故障不得伪装成成功，任务落 FAILED。 */
    @Test void workerUnavailableFailsTaskNotSucceeded() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        TaskOrchestrator orchestrator = orchestrator(sequenceExecutor(fullSuccessSequence()));
        when(finalDiffBundles.createPendingBatch(any(), any(), any())).thenThrow(
                new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", "Sandbox worker is unavailable"));

        orchestrator.orchestrate(projectId, taskId);

        assertTerminalStatus("FAILED");
        verify(eventService, never()).publish(any(), any(), eq("diff-review.skipped"), any(), any());
    }

    /** 内部一致性错误（FINAL_CODING_RUN_INVALID）同样不得降级成功。 */
    @Test void internalConsistencyErrorFailsTask() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        TaskOrchestrator orchestrator = orchestrator(sequenceExecutor(fullSuccessSequence()));
        when(finalDiffBundles.createPendingBatch(any(), any(), any())).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "FINAL_CODING_RUN_INVALID",
                        "A successful final coding run is required"));

        orchestrator.orchestrate(projectId, taskId);

        assertTerminalStatus("FAILED");
    }

    /** 非 ApiException 的运行时异常（意外错误）不伪装成功，任务落 FAILED。 */
    @Test void unexpectedDiffCreationFailureFailsTask() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubStepScheduler(taskId);
        stubRunCreation(projectId, taskId);
        stubPlanPersistence(task);

        TaskOrchestrator orchestrator = orchestrator(sequenceExecutor(fullSuccessSequence()));
        when(finalDiffBundles.createPendingBatch(any(), any(), any()))
                .thenThrow(new IllegalStateException("unexpected failure"));

        orchestrator.orchestrate(projectId, taskId);

        assertTerminalStatus("FAILED");
        verify(eventService, never()).publish(any(), any(), eq("diff-review.skipped"), any(), any());
    }
}
