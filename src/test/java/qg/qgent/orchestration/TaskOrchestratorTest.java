package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextSkill;
import qg.qgent.dto.GroupContext;
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
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.SandboxWorkerProperties;
import qg.qgent.service.EventService;
import qg.qgent.service.MessageService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.OrchestratorAgentService;
import qg.qgent.service.TaskRunService;
import qg.qgent.service.TaskService;
import qg.qgent.service.TaskExecutionArtifactService;
import qg.qgent.service.FinalDiffBundleService;
import qg.qgent.service.ContextService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * TaskOrchestrator 主链路 Mockito 测试：驱动数据驱动图（step=node）整条
 * PLANNER→DEVELOPER→TESTER→REVIEWER 循环，断言 Task 终态、模板步骤创建、TaskRun 角色链、
 * 重试/质量循环的反馈装配与终态接线（diff-batch/卡片/事件）。
 * 纯单元测试：全部依赖 Mock，不启动 Spring 上下文、不访问 DB。
 */
class TaskOrchestratorTest {
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final TaskStepMapper stepMapper = mock(TaskStepMapper.class);
    private final WorkspaceRepositoryMapper repoMapper = mock(WorkspaceRepositoryMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TaskRunService taskRunService = mock(TaskRunService.class);
    private final EventService eventService = mock(EventService.class);
    private final TaskExecutionArtifactService artifactService = mock(TaskExecutionArtifactService.class);
    private final FinalDiffBundleService finalDiffBundles = mock(FinalDiffBundleService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final OrchestratorAgentService orchestratorAgents = mock(OrchestratorAgentService.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private static final UUID AGENT_ID = UUID.randomUUID();
    /** 团队编排助手（卡片统一发送者）ID，由 OrchestratorAgentService 解析。 */
    private static final UUID ORCHESTRATOR_ID = UUID.randomUUID();
    private final ContextService contextService = mock(ContextService.class);
    private final AgentContextAssembler contextAssembler = new AgentContextAssembler(contextService);
    private final GitHubAppClient githubAppClient = mock(GitHubAppClient.class);
    /** 默认禁用 Worker 的会话管理器；启动失败用例替换为启用且抛错的版本。 */
    private SandboxSessionManager sessionManager = new SandboxSessionManager(
            mock(SandboxWorkerClient.class), new SandboxWorkerProperties(), mock(WorkspaceMapper.class), repoMapper,
            mock(ProjectRepositoryMapper.class), mock(qg.qgent.mapper.GitHubRepositoryMapper.class),
            mock(qg.qgent.mapper.GitHubInstallationMapper.class), mock(qg.qgent.service.GitCredentialService.class), githubAppClient);

    private TaskOrchestrator orchestrator(AgentRegistry registry) {
        when(finalDiffBundles.createPendingBatch(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(orchestratorAgents.resolveIdForTask(any())).thenReturn(ORCHESTRATOR_ID);
        when(taskMapper.claimForOrchestration(any(), any())).thenReturn(1);
        when(taskMapper.claimForResume(any(), any())).thenReturn(1);
        return new TaskOrchestrator(new OrchestrationStateMachine(), new WorkflowGraphBuilder(), registry,
                contextAssembler, taskService, taskRunService, taskMapper, stepMapper, repoMapper, eventService,
                notificationService, sessionManager, artifactService, finalDiffBundles, messageService,
                agentMapper, projectMapper, orchestratorAgents);
    }

    private AgentRegistry registryOf(Agent agent) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.resolve(any(), any())).thenReturn(Optional.of(agent));
        return registry;
    }

    /** 按相位返回成功结果的 mock Agent（PLANNER 产出计划，其余 SUCCEEDED）。 */
    private Agent phaseAgent() {
        Agent agent = mock(Agent.class);
        when(agent.run(any())).thenAnswer(inv -> {
            AgentInput input = inv.getArgument(0);
            if (input.getPhase() == OrchestrationPhase.PLAN) {
                return planSuccess();
            }
            return outcome(input.getPhase(), RunOutcome.SUCCEEDED);
        });
        return agent;
    }

    /** 按调用顺序返回预设结果的 mock Agent（位置与节点执行顺序对应）。 */
    private Agent sequenceAgent(List<AgentRunOutcome> sequence) {
        AtomicInteger idx = new AtomicInteger();
        Agent agent = mock(Agent.class);
        when(agent.run(any())).thenAnswer(inv -> sequence.get(idx.getAndIncrement()));
        return agent;
    }

    /** 以 sequenceAgent 驱动整条链路并执行。 */
    private Agent runSequence(TaskEntity task, List<AgentRunOutcome> sequence) {
        Agent agent = sequenceAgent(sequence);
        orchestrator(registryOf(agent)).orchestrate(task.getProjectId(), task.getId());
        return agent;
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

    private TaskStepEntity step(UUID taskId, String role, int sequenceNo) {
        TaskStepEntity s = new TaskStepEntity();
        s.setId(UUID.randomUUID());
        s.setTaskId(taskId);
        s.setRole(role);
        s.setInstruction("execute " + role);
        s.setStatus("PENDING");
        s.setSequenceNo(sequenceNo);
        s.setAssignedAgentId(AGENT_ID);
        return s;
    }

    private List<TaskStepEntity> canonicalSteps(UUID taskId) {
        return List.of(step(taskId, "PLANNER", 1), step(taskId, "DEVELOPER", 2),
                step(taskId, "TESTER", 3), step(taskId, "REVIEWER", 4));
    }

    /** 预置 4 个规范步骤（PLANNER/DEVELOPER/TESTER/REVIEWER，assignedAgentId=AGENT_ID），
     * 使 ensureSteps 跳过模板创建，selectById 按 stepId 回查。返回被 stub 的步骤列表。 */
    private List<TaskStepEntity> stubSteps(TaskEntity task) {
        List<TaskStepEntity> steps = canonicalSteps(task.getId());
        Map<UUID, TaskStepEntity> byId = new HashMap<>();
        for (TaskStepEntity s : steps) {
            byId.put(s.getId(), s);
        }
        when(stepMapper.selectList(any())).thenReturn(steps);
        when(stepMapper.selectById(any())).thenAnswer(inv -> byId.get(inv.getArgument(0)));
        return steps;
    }

    private void stubRunCreation(UUID projectId, UUID taskId) {
        when(taskRunService.createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TaskRunEntity run = new TaskRunEntity();
                    run.setId(UUID.randomUUID());
                    return run;
                });
    }

    /** 模板创建路径：仓库 scope 来源 + resolveAgent 用的项目/团队 Agent。 */
    private void stubPlanPersistence(TaskEntity task) {
        WorkspaceRepositoryEntity repo = new WorkspaceRepositoryEntity();
        repo.setProjectRepositoryId(UUID.randomUUID());
        when(repoMapper.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repo));
        stubAgents(task);
    }

    /** 方案 A：团队内存在与角色匹配的 ACTIVE TEAM Agent，供 resolveAgent 分配与回群断言。 */
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

    private void assertTerminalStatus(String expected) {
        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(taskMapper, atLeast(1)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus()).isEqualTo(expected);
    }

    @Test void happyPathCompletesSuccessWithPlanStepsAndFourRuns() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        orchestrator(registryOf(phaseAgent())).orchestrate(projectId, taskId);

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        // 步骤预置时 ensureSteps 幂等：不重复创建模板
        verify(taskService, never()).addSteps(any(), any(), any(), any());

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> agentCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskRunService, times(4)).createForStep(eq(projectId), eq(taskId), any(), roleCaptor.capture(),
                agentCaptor.capture(), any(), any());
        assertThat(roleCaptor.getAllValues()).containsExactly("PLANNER", "DEVELOPER", "TESTER", "REVIEWER");
        // 方案 A：TaskRun.agentId 来自步骤分配的真实 Agent，不再恒 null
        assertThat(agentCaptor.getAllValues()).allSatisfy(agentId -> assertThat(agentId).isEqualTo(AGENT_ID));
        verify(taskRunService, times(4)).complete(any(), anyString());

        // 卡片接线：全部卡片由团队编排助手（ORCHESTRATOR_ID）发送，TASK_STATUS 类型、幂等键前缀正确
        ArgumentCaptor<MessageSendRequest> reqCaptor = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(messageService, atLeast(1)).sendAsAgent(eq(task.getRequirementGroupId()), eq(ORCHESTRATOR_ID),
                reqCaptor.capture());
        assertThat(reqCaptor.getAllValues()).allSatisfy(req -> assertThat(req.getType()).isEqualTo("TASK_STATUS"));
        assertThat(reqCaptor.getAllValues()).anyMatch(req -> req.getClientMessageId().startsWith("agent-step-"));
        assertThat(reqCaptor.getAllValues()).anyMatch(req -> req.getClientMessageId().startsWith("agent-task-"));
    }

    /** 群聊/Skill/Memory 上下文：ContextService.buildForGroup 每次 orchestrate 拉取一次（limit=50），
     * 快照注入每个 step 的 AgentInput。 */
    @Test void groupContextSnapshotFlowsIntoEveryStepInput() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        GroupContext groupContext = new GroupContext(task.getRequirementGroupId().toString(), projectId.toString(),
                "需求群", "背景说明", List.of("repo-1"),
                List.of(new ContextMessage(1L, "TEXT", "USER", "u-1", "补充：需要离线导出")),
                List.of(new ContextSkill("编码规范", "禁止提交 .env")),
                List.of(new ContextMemory("缓存约定", "Redis key 以 projectId 前缀", "architecture")));
        when(contextService.buildForGroup(any(), any(), any(), any())).thenReturn(groupContext);

        Agent agent = phaseAgent();
        orchestrator(registryOf(agent)).orchestrate(projectId, taskId);

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        // 一次 orchestrate 只组装一次，limit 固定 50
        verify(contextService, times(1)).buildForGroup(eq(task.getCreatedBy()), eq(projectId),
                eq(task.getRequirementGroupId()), eq(50));
        // 快照注入每个 step 输入，且与来源一致（同一对象复用）
        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(agent, times(4)).run(inputCaptor.capture());
        assertThat(inputCaptor.getAllValues()).allSatisfy(input -> {
            assertThat(input.getConversation()).containsExactly(groupContext.getConversation().get(0));
            assertThat(input.getSkills()).containsExactly(groupContext.getSkills().get(0));
            assertThat(input.getMemories()).containsExactly(groupContext.getMemories().get(0));
        });
    }

    /** ContextService 组装失败（群不存在等）不阻断编排：AgentInput 上下文为空列表语义。 */
    @Test void contextAssemblyFailureDoesNotFailOrchestration() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);
        when(contextService.buildForGroup(any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在"));

        Agent agent = phaseAgent();
        orchestrator(registryOf(agent)).orchestrate(projectId, taskId);

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(agent, times(4)).run(inputCaptor.capture());
        assertThat(inputCaptor.getAllValues()).allSatisfy(input -> {
            assertThat(input.getConversation()).isNull();
            assertThat(input.getSkills()).isNull();
            assertThat(input.getMemories()).isNull();
        });
    }

    /** 任务无步骤：ensureSteps 创建模板四步（PLANNER/DEVELOPER/TESTER/REVIEWER），resolveAgent 落 assignedAgentId。 */
    @Test void noStepsCreatesTemplateStepsBeforeExecution() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubPlanPersistence(task);
        stubRunCreation(projectId, taskId);
        // 首次 loadSteps 为空 → 创建模板；随后 loadSteps 返回模板步骤（含 resolveAgent 定型的 assignedAgentId）
        List<TaskStepEntity> steps = canonicalSteps(taskId);
        Map<UUID, TaskStepEntity> byId = new HashMap<>();
        for (TaskStepEntity s : steps) {
            byId.put(s.getId(), s);
        }
        when(stepMapper.selectList(any())).thenReturn(List.of(), steps);
        when(stepMapper.selectById(any())).thenAnswer(inv -> byId.get(inv.getArgument(0)));

        orchestrator(registryOf(phaseAgent())).orchestrate(projectId, taskId);

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        verify(taskService).addSteps(eq(projectId), eq(taskId), eq(task.getCreatedBy()),
                argThat(requests -> requests.size() == 4
                        && requests.stream().map(r -> r.getRole()).toList()
                                .containsAll(List.of("PLANNER", "DEVELOPER", "TESTER", "REVIEWER"))
                        && requests.stream().allMatch(r -> AGENT_ID.equals(r.getAssignedAgentId()))));
        verify(taskRunService, times(4)).createForStep(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void testFailureRequeuesCodingWithFeedbackThenSucceeds() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        Agent agent = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        verify(taskRunService, times(6)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any());
        verify(taskRunService, times(6)).complete(any(), anyString());
        verify(agent, times(6)).run(any());
    }

    @Test void testFailureFeedsFeedbackIntoRetryCodingInput() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        Agent agent = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(agent, times(6)).run(inputCaptor.capture());
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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        TestResult failing = new TestResult();
        failing.setSuccess(false);
        failing.setSummary("2 tests failed");
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName("CalculatorTest.addShouldSum");
        failure.setReason("expected 5 but got 4");
        failing.setFailures(List.of(failure));

        AgentRunOutcome testingFailed = outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        testingFailed.setTestResult(failing);

        Agent agent = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                testingFailed,
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");

        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(agent, times(6)).run(inputCaptor.capture());
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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        AgentRunOutcome reviewFailed = outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        reviewFailed.setReviewResult(reviewWithFindings());

        Agent agent = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                reviewFailed,
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        verify(taskRunService, times(7)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any());
        verify(taskRunService, times(7)).complete(any(), anyString());
        verify(agent, times(7)).run(any());
    }

    /** §七：ReviewResult.findings/suggestions 必须进入重试 Coding Agent 的输入（feedback）。 */
    @Test void reviewFindingsFeedIntoRetryCodingInput() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        AgentRunOutcome reviewFailed = outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        reviewFailed.setReviewResult(reviewWithFindings());

        Agent agent = runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                reviewFailed,
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");

        ArgumentCaptor<AgentInput> inputCaptor = ArgumentCaptor.forClass(AgentInput.class);
        verify(agent, times(7)).run(inputCaptor.capture());
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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.FAILED_INFRASTRUCTURE),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> retryCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskRunService, times(5)).createForStep(eq(projectId), eq(taskId), any(), roleCaptor.capture(),
                any(), any(), retryCaptor.capture());
        List<UUID> devRetries = new ArrayList<>();
        for (int i = 0; i < roleCaptor.getAllValues().size(); i++) {
            if ("DEVELOPER".equals(roleCaptor.getAllValues().get(i))) {
                devRetries.add(retryCaptor.getAllValues().get(i));
            }
        }
        assertThat(devRetries).hasSize(2);
        assertThat(devRetries.get(0)).isNull();
        assertThat(devRetries.get(1)).isNotNull();
    }

    @Test void cancelledCompletesCancelled() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        runSequence(task, List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.CANCELLED)));

        assertTerminalStatus("CANCELLED");
    }

    /** PLANNER 提升为正式 step：plan 失败会建一次 TaskRun 后任务 FAILED。 */
    @Test void planFailureFailsTaskAfterOneRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        runSequence(task, List.of(outcome(OrchestrationPhase.PLAN, RunOutcome.FAILED)));

        assertTerminalStatus("FAILED");
        verify(taskRunService, times(1)).createForStep(any(), any(), any(), any(), any(), any(), any());
    }
    @Test
    void notStartableTaskRejected() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setStatus("SUCCEEDED");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        TaskOrchestrator orch = orchestrator(mock(AgentRegistry.class));
        // 终态任务认领失败（claimForOrchestration 返回 0）→ 拒绝
        when(taskMapper.claimForOrchestration(projectId, taskId)).thenReturn(0);

        assertThatThrownBy(() -> orch.orchestrate(projectId, taskId))
                .isInstanceOf(IllegalStateException.class);
        verify(taskRunService, never()).createForStep(any(), any(), any(), any(), any(), any(), any());
    }

    /** Worker 不可达等启动失败：任务落 FAILED、发布 task.updated、写 TASK_FAILED 通知并以编排助手身份回群卡片。 */
    @Test void sandboxUnavailableFailsTaskAndNotifies() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        sessionManager = failingSessionManager();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> orchestrator(mock(AgentRegistry.class)).orchestrate(projectId, taskId));

        assertTerminalStatus("FAILED");
        verify(notificationService).notify(eq(task.getCreatedBy()), eq(projectId), eq(task.getRequirementGroupId()),
                eq("TASK_FAILED"), anyString(), anyString(), eq(taskId.toString()));
        verify(messageService).sendAsAgent(eq(task.getRequirementGroupId()), eq(ORCHESTRATOR_ID), any());
        verify(messageService, never()).sendAsSystem(any(), any());
    }

    /** 启动失败时任务已被并发取消（CANCELLED 终态）：不覆盖状态、不写失败通知。 */
    @Test void startupFailureDoesNotOverwriteCancelledTask() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        TaskEntity cancelled = task(projectId, taskId);
        cancelled.setStatus("CANCELLED");
        // 第一次 selectById 供 requireTask（PLANNING 可启动），第二次供 failStartup 重查（已取消）
        when(taskMapper.selectById(taskId)).thenReturn(task, cancelled);
        sessionManager = failingSessionManager();

        orchestrator(mock(AgentRegistry.class)).orchestrate(projectId, taskId);

        verify(taskMapper, never()).updateById(any(TaskEntity.class));
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), any());
    }

    /** 团队查不到编排助手时卡片降级为 SYSTEM 系统消息，不因缺少发送者而丢失。 */
    @Test void missingOrchestratorAgentDegradesCardToSystemMessage() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        sessionManager = failingSessionManager();

        TaskOrchestrator orchestrator = orchestrator(mock(AgentRegistry.class));
        when(orchestratorAgents.resolveIdForTask(any())).thenReturn(null);
        orchestrator.orchestrate(projectId, taskId);

        assertTerminalStatus("FAILED");
        verify(messageService).sendAsSystem(eq(task.getRequirementGroupId()), any());
        verify(messageService, never()).sendAsAgent(any(), any(), any());
    }

    /** 启用 Worker 且 workspace 查询失败：acquire 抛错，用于构造启动阶段基础设施故障。 */
    private SandboxSessionManager failingSessionManager() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setEnabled(true);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        when(workspaceMapper.selectById(any(UUID.class))).thenReturn(null);
        return new SandboxSessionManager(mock(SandboxWorkerClient.class), properties, workspaceMapper,
                repoMapper, mock(ProjectRepositoryMapper.class),
                mock(qg.qgent.mapper.GitHubRepositoryMapper.class),
                mock(qg.qgent.mapper.GitHubInstallationMapper.class),
                mock(qg.qgent.service.GitCredentialService.class), githubAppClient);
    }

    /** 认领防重：同一任务被并发认领时（claim 返回 0）编排拒绝，杜绝任务被反复调用。 */
    @Test
    void alreadyClaimedTaskRejected() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setStatus("RUNNING");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        TaskOrchestrator orch = orchestrator(mock(AgentRegistry.class));
        when(taskMapper.claimForOrchestration(projectId, taskId)).thenReturn(0);

        assertThatThrownBy(() -> orch.orchestrate(projectId, taskId))
                .isInstanceOf(IllegalStateException.class);
        verify(taskRunService, never()).createForStep(any(), any(), any(), any(), any(), any(), any());
    }

    /** 失败任务续跑：从指定 step 开始执行，仍走完整状态机与终态 diff-batch；首个 run 携带 retryOfTaskRunId。 */
    @Test
    void resumeFromFailedStepCompletesToDiffConfirmation() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setStatus("FAILED");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        List<TaskStepEntity> steps = stubSteps(task);
        stubRunCreation(projectId, taskId);
        UUID reviewerStepId = steps.get(3).getId();

        // 从 REVIEWER 开始，本次无成功 CODING run → 终态兜底查询最近一次 SUCCEEDED DEVELOPER run
        TaskRunEntity lastCoding = new TaskRunEntity();
        lastCoding.setId(UUID.randomUUID());
        lastCoding.setTaskId(taskId);
        lastCoding.setRole("DEVELOPER");
        lastCoding.setStatus("SUCCEEDED");
        when(taskMapper.selectLastSucceededCodingRunId(taskId)).thenReturn(lastCoding.getId());

        UUID sourceRunId = UUID.randomUUID();
        Agent agent = sequenceAgent(List.of(
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)));
        orchestrator(registryOf(agent)).orchestrate(projectId, taskId, reviewerStepId, sourceRunId);

        assertTerminalStatus("WAITING_DIFF_CONFIRMATION");
        // 续跑只执行该 step 一次，requeue 目标节点仍指向 DEVELOPER（不因 startStep 改变）
        verify(taskRunService, times(1)).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(), any());
        // 续跑产生的首个 run 的 retryOfTaskRunId 指向被重试的源失败运行
        ArgumentCaptor<UUID> retryCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskRunService).createForStep(eq(projectId), eq(taskId), any(), any(), any(), any(),
                retryCaptor.capture());
        assertThat(retryCaptor.getValue()).isEqualTo(sourceRunId);
        // 终态 diff-batch 使用兜底的最近成功 CODING run
        verify(finalDiffBundles).createPendingBatch(eq(projectId), eq(taskId), eq(lastCoding.getId()));
    }

    /** 续跑时任务仍 RUNNING（编排中）→ 认领拒绝，不重复编排。 */
    @Test
    void resumeRejectedWhileTaskRunning() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setStatus("RUNNING");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        TaskOrchestrator orch = orchestrator(mock(AgentRegistry.class));
        when(taskMapper.claimForResume(projectId, taskId)).thenReturn(0);

        assertThatThrownBy(() -> orch.orchestrate(projectId, taskId, stepId))
                .isInstanceOf(IllegalStateException.class);
        verify(taskRunService, never()).createForStep(any(), any(), any(), any(), any(), any(), any());
    }

    /** 终态兜底：上下文无 lastCodingRunId 且 DB 也查不到成功 CODING run → FINAL_CODING_RUN_INVALID 落 FAILED。 */
    @Test
    void resumeWithoutAnyCodingRunFailsWhenDiffBatchNeedsCodingRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        task.setStatus("FAILED");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        List<TaskStepEntity> steps = stubSteps(task);
        stubRunCreation(projectId, taskId);
        UUID reviewerStepId = steps.get(3).getId();
        when(taskMapper.selectLastSucceededCodingRunId(taskId)).thenReturn(null);
        TaskOrchestrator orch = orchestrator(registryOf(sequenceAgent(
                List.of(outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED)))));
        when(finalDiffBundles.createPendingBatch(any(), any(), any())).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "FINAL_CODING_RUN_INVALID",
                        "A successful final coding run is required"));

        orch.orchestrate(projectId, taskId, reviewerStepId, null);

        assertTerminalStatus("FAILED");
    }

    private List<AgentRunOutcome> fullSuccessSequence() {
        return List.of(
                planSuccess(),
                outcome(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED),
                outcome(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED));
    }

    /** 后端3 决策：无未提交改动（FINAL_DIFF_EMPTY）视为成功降级 SUCCEEDED，且发布 diff-review.skipped 事件作为依据。 */
    @Test void finalDiffEmptyDegradesToSucceededWithSkippedEvent() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        TaskOrchestrator orchestrator = orchestrator(registryOf(sequenceAgent(fullSuccessSequence())));
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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        TaskOrchestrator orchestrator = orchestrator(registryOf(sequenceAgent(fullSuccessSequence())));
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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        TaskOrchestrator orchestrator = orchestrator(registryOf(sequenceAgent(fullSuccessSequence())));
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
        stubSteps(task);
        stubRunCreation(projectId, taskId);

        TaskOrchestrator orchestrator = orchestrator(registryOf(sequenceAgent(fullSuccessSequence())));
        when(finalDiffBundles.createPendingBatch(any(), any(), any()))
                .thenThrow(new IllegalStateException("unexpected failure"));

        orchestrator.orchestrate(projectId, taskId);

        assertTerminalStatus("FAILED");
        verify(eventService, never()).publish(any(), any(), eq("diff-review.skipped"), any(), any());
    }
}
