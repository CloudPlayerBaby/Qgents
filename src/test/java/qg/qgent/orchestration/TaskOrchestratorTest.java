package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import qg.qgent.dto.GroupContext;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.service.EventService;
import qg.qgent.service.FinalDiffBundleService;
import qg.qgent.service.MessageService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.OrchestratorAgentService;
import qg.qgent.service.TaskExecutionArtifactService;
import qg.qgent.service.TaskRunFailureDiagnosticService;
import qg.qgent.service.TaskPlanMaterializationService;
import qg.qgent.service.TaskRunService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Planner bootstrap 编排测试。PLAN 只产生 Task 级产物，正式图只消费已物化并冻结的执行步骤。
 */
class TaskOrchestratorTest {

    @Test
    void workspaceLeaseConflictDefersTaskWithoutReleasingCurrentWriterSession() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        doThrow(new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                "WORKSPACE_WRITE_LEASE_HELD", "another task is writing"))
                .when(fixture.sessions).acquire(task.getId(), task.getProjectId(), task.getWorkspaceId());
        when(fixture.tasks.deferForWorkspaceWriteLease(task.getProjectId(), task.getId())).thenAnswer(invocation -> {
            task.setStatus("PENDING");
            return 1;
        });

        fixture.orchestrator(fixture.sequenceAgent()).orchestrate(task.getProjectId(), task.getId());

        verify(fixture.tasks).deferForWorkspaceWriteLease(task.getProjectId(), task.getId());
        verify(fixture.events).publish(eq(task.getProjectId()), eq(task.getRequirementGroupId()),
                eq("task.updated"), eq(task.getId().toString()), any());
        verify(fixture.notifications, never()).notify(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.sessions).release(task.getWorkspaceId(), task.getId());
        assertThat(fixture.updatedStatuses()).doesNotContain("FAILED");
    }

    @Test
    void plannerCreatesItsOwnRunAndFormalGraphStartsAtDeveloper() {
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

        // Planner 也有自己的持久化 Run（用于心跳与启动失败记录），正式图从 Developer 开始。
        verify(fixture.taskRuns, times(4)).createForStep(eq(task.getProjectId()), eq(task.getId()), any(),
                anyString(), any(), any(), any());
        verify(fixture.taskRuns).createForStep(eq(task.getProjectId()), eq(task.getId()), eq(planner.getId()),
                anyString(), any(), any(), any());
        verify(fixture.materialization).materialize(eq(task), any(PlanResult.class));
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("CODING"), any());
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("TESTING"), any());
        verify(fixture.artifacts).createRunArtifact(any(), any(), any(), eq("REVIEWING"), any());
    }

    @Test
    void settlesCurrentStepWhenRunCreationFailsAfterStepMarkedRunning() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity first = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity failed = fixture.step(task, "DEVELOPER", 3);
        TaskStepEntity following = fixture.step(task, "DEVELOPER", 4);
        List<TaskStepEntity> all = List.of(planner, first, failed, following);
        fixture.stubPlan(task, planner, all);
        doThrow(new RuntimeException("task run insert failed")).when(fixture.taskRuns).createForStep(
                eq(task.getProjectId()), eq(task.getId()), eq(failed.getId()), anyString(), any(), any(), any());

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(first.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(following.getStatus()).isEqualTo("PENDING");
        assertThat(fixture.captureStepStatuses()).containsSubsequence(
                failed.getId() + "=RUNNING", failed.getId() + "=FAILED");
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

        ArgumentCaptor<MessageSendRequest> diffCaptor = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(fixture.messages).upsertDiffCard(eq(task.getRequirementGroupId()), any(), diffCaptor.capture());
        MessageSendRequest diffRequest = diffCaptor.getValue();
        assertThat(diffRequest.getContent().get("diffId")).isEqualTo(diff.getId().toString());
        assertThat(diffRequest.getContent().get("title")).isEqualTo(task.getTitle());
        assertThat(diffRequest.getContent().get("additions")).isEqualTo(5);
        assertThat(diffRequest.getContent().get("deletions")).isEqualTo(3);
    }

    @Test
    void sendingDiffCardFallsBackToSystemWithoutLosingDiffType() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        when(fixture.orchestratorAgents.resolveIdForTask(task)).thenReturn(null);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        UUID batchId = UUID.randomUUID();
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID());
        diff.setChangeStats(Map.of("additions", 2, "deletions", 1));
        when(fixture.diffs.createPendingBatch(any(), any(), any())).thenReturn(batchId);
        when(fixture.diffMapper.selectList(any())).thenReturn(List.of(diff));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        ArgumentCaptor<MessageSendRequest> cards = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(fixture.messages, atLeastOnce()).upsertDiffCard(eq(task.getRequirementGroupId()), isNull(), cards.capture());
        MessageSendRequest diffCard = cards.getAllValues().stream()
                .filter(card -> "DIFF".equals(card.getType()))
                .findFirst().orElseThrow();
        assertThat(diffCard.getContent()).containsEntry("diffId", diff.getId().toString());
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
        // TESTING 失败统一进 REVIEWING：由 Review 判 FAILED_QUALITY 触发 requeue（回到最后
        // 一个可写 developer），不再由 Test 自判直接打回。REVIEWING SUCCESS 后任务成功。
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.CODING), fixture.outcome(OrchestrationPhase.TESTING,
                        RunOutcome.FAILED_QUALITY), fixture.outcome(OrchestrationPhase.REVIEWING,
                        RunOutcome.FAILED_QUALITY), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developerOne.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developerTwo.getId()), anyString(), any(), any(), any());
    }

    @Test
    void testQualityFailureResetsReworkChainToPending() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        TestResult testResult = new TestResult();
        testResult.setSuccess(false);
        testResult.setNeedsCodingFix(true);
        failedTest.setTestResult(testResult);
        // 质量闭环改由 REVIEWING FAILED_QUALITY 触发 requeue：TESTING 失败先交 Review 裁决。
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.captureStepStatuses()).contains(developer.getId() + "=PENDING", tester.getId() + "=PENDING",
                reviewer.getId() + "=PENDING");
    }

    @Test
    void verifyDeveloperStepUsesTestingPhaseAndQualityFailureReturnsToLastMutableStep() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        developer.setExecutionMode("MUTATE");
        TaskStepEntity verification = fixture.step(task, "DEVELOPER", 3);
        verification.setExecutionMode("VERIFY");
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, verification, reviewer);
        fixture.stubPlan(task, planner, all);
        Agent agent = fixture.sequenceAgent(
                fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING),
                fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY),
                fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING));

        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // VERIFY 虽然保留 DEVELOPER role，但应按 TESTING 进入验证；Test 不自判失败，测试失败
        // 交 Review 裁决，Review 判质量失败后回到真正可写的 MUTATE developer，而不是同一个只读
        // VERIFY step。Review 先判一次（FAILED_QUALITY）再确认一次（SUCCESS）。
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(verification.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(reviewer.getId()), anyString(), any(), any(), any());
        assertThat(fixture.feedbacksFor(OrchestrationPhase.TESTING)).hasSize(2);
    }

    @Test
    void qualityFailureInReadOnlyTaskIsReleasedWithoutLoopingBack() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity verification = fixture.step(task, "DEVELOPER", 2);
        verification.setExecutionMode("VERIFY");
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 3);
        List<TaskStepEntity> all = List.of(planner, verification, reviewer);
        fixture.stubPlan(task, planner, all);
        fixture.orchestrator(fixture.sequenceAgent(
                fixture.planSuccess(),
                fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY),
                fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY)))
                .orchestrate(task.getProjectId(), task.getId());

        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(verification.getId()), anyString(), any(), any(), any());
        // TESTING 失败统一进 REVIEWING，Review 判质量失败后 requeue；但任务没有可写 MUTATE 步骤，
        // requeue 被守卫拦截并放行（reviewer 已运行一次），不放行则重复验证同一事实直到耗尽循环。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(reviewer.getId()), anyString(), any(), any(), any());
        // 无 MUTATE 步骤 ⇒ 无代码交付，任务以 SUCCEEDED（无代码变更）收敛，不再判 FAILED；
        // 放行原因如实写入 Run 产物，不隐藏 Review 发现的问题。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(task.getFailureCode()).isNull();
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                "审查发现问题但无代码修改步骤可修复，已作为审查结论记录"
                        .equals(summary.get("bypassReason")));
    }

    @Test
    void testQualityFailureWithoutCodingFixStillRoutesToReview() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome failedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        TestResult testResult = new TestResult();
        testResult.setSuccess(false);
        testResult.setNeedsCodingFix(false);
        testResult.setSummary("测试环境不可用");
        failedTest.setTestResult(testResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), failedTest,
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        // Test 的 needsCodingFix=false 不再拦截路由：TESTING 失败统一交 Review，Review 判
        // SUCCESS（无 BLOCKER/MAJOR）→ 任务成功。needsCodingFix 仅作分析信息，不再影响去向。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
    }

    @Test
    void reviewSuccessWithoutMutableStepFinishesWithoutFinalCodingRun() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity verification = fixture.step(task, "DEVELOPER", 2);
        verification.setExecutionMode("VERIFY");
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 3);
        fixture.stubPlan(task, planner, List.of(planner, verification, reviewer));

        fixture.orchestrator(fixture.sequenceAgent(
                fixture.planSuccess(),
                fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        verify(fixture.diffs, never()).createPendingBatch(any(), any(), any());
        verify(fixture.events).publish(eq(task.getProjectId()), eq(task.getRequirementGroupId()),
                eq("diff-review.skipped"), eq(task.getId().toString()), any());
    }

    @Test
    void qualityLoopExhaustionDeliversDiffInsteadOfFailing() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        // 质量循环上限 3 次：质量闭环改由 REVIEWING FAILED_QUALITY 驱动 requeue，前 3 次
        // 触发 requeue，第 4 次循环耗尽。每个闭环 = Test 失败交 Review → Review 判失败 → 回 Coding。
        AgentRunOutcome failedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        TestResult repairNeeded = new TestResult();
        repairNeeded.setSuccess(false);
        repairNeeded.setNeedsCodingFix(true);
        repairNeeded.setSummary("测试未通过，需要修复");
        failedTest.setTestResult(repairNeeded);
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview))
                .orchestrate(task.getProjectId(), task.getId());

        // 循环耗尽不再以任务 FAILED 收场：改为交付当前代码供用户人工核对（DIFF_FIRST 生成待确认
        // Diff，findings 已在审查产物里完整保留），卡片如实标注放行原因，不伪装成审查通过。
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(fixture.updatedStatuses()).doesNotContain("FAILED");
        assertThat(task.getFailureCode()).isNull();
        verify(fixture.diffs, times(1)).createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("修复循环已耗尽"));
    }

    @Test
    void failedPlannerCreatesOnlyItsOwnRunNotFormalGraph() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        fixture.stubPlan(task, planner, List.of(planner));

        fixture.orchestrator(fixture.sequenceAgent(fixture.outcome(OrchestrationPhase.PLAN, RunOutcome.FAILED)))
                .orchestrate(task.getProjectId(), task.getId());

        verify(fixture.materialization, never()).materialize(any(), any());
        // Planner 失败仍持久化自己的 Run，但不物化正式步骤、不进入 Developer 等正式图。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()), eq(planner.getId()),
                anyString(), any(), any(), any());
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

        // 物化失败：Planner Run 仍被持久化并落 FAILED，但不再创建 Developer 等正式 Run。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()), eq(planner.getId()),
                anyString(), any(), any(), any());
        verify(fixture.taskRuns).complete(any(), eq("FAILED"), eq("FAILED_INFRASTRUCTURE"), any());
        assertThat(planner.getStatus()).isEqualTo("FAILED");
        verify(fixture.steps, times(2)).updateById(planner);
        assertThat(fixture.updatedStatuses()).contains("FAILED");
    }

    @Test
    void sandboxAcquireExhaustedPutsTaskInFailedWithoutTaskRun() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        doThrow(new RuntimeException("sandbox worker down"))
                .when(fixture.sessions).acquire(any(), any(), any());

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess()))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.updatedStatuses()).contains("FAILED");
        verifyNoInteractions(fixture.taskRuns);
        verify(fixture.sessions, times(1)).acquire(any(), any(), any());
    }

    @Test
    void startupFailurePersistsStableUserVisibleReason() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        doThrow(new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "SANDBOX_WORKER_ERROR", "502 Bad Gateway: internal worker url"))
                .when(fixture.sessions).acquire(any(), any(), any());

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess()))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(task.getFailureCode()).isEqualTo("SANDBOX_WORKER_ERROR");
        assertThat(task.getFailureReason()).isEqualTo("Sandbox Worker 当前不可用");
        assertThat(task.getFailureReason()).doesNotContain("internal worker url");
        assertThat(task.getFailureRetryable()).isTrue();

        ArgumentCaptor<MessageSendRequest> cards = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(fixture.messages, atLeastOnce()).upsertTaskStatusCard(eq(task.getRequirementGroupId()), any(), cards.capture());
        MessageSendRequest failed = cards.getAllValues().stream()
                .filter(card -> "FAILED".equals(card.getContent().get("status")))
                .findFirst().orElseThrow();
        String failedMessage = String.valueOf(failed.getContent().get("message"));
        assertThat(failedMessage)
                .isEqualTo("任务启动失败：执行环境不可用。Sandbox Worker 当前不可用，可以稍后重试")
                .doesNotContain("internal worker url");
    }

    @Test
    void startupFailureKeepsGitBranchNotFoundWithRepositoryContext() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        doThrow(new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "GIT_BRANCH_NOT_FOUND",
                "仓库 CloudPlayerBaby/test01 不存在基线分支 develop",
                java.util.List.of(java.util.Map.of("repository", "CloudPlayerBaby/test01",
                        "branch", "develop", "fullName", "CloudPlayerBaby/test01"))))
                .when(fixture.sessions).acquire(any(), any(), any());

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess()))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(task.getFailureCode()).isEqualTo("GIT_BRANCH_NOT_FOUND");
        assertThat(task.getFailureReason())
                .contains("CloudPlayerBaby/test01")
                .contains("develop")
                .contains("基线分支");
        assertThat(task.getFailureRetryable()).isTrue();

        ArgumentCaptor<MessageSendRequest> cards = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(fixture.messages, atLeastOnce()).upsertTaskStatusCard(eq(task.getRequirementGroupId()), any(), cards.capture());
        MessageSendRequest failed = cards.getAllValues().stream()
                .filter(card -> "FAILED".equals(card.getContent().get("status")))
                .findFirst().orElseThrow();
        String failedMessage = String.valueOf(failed.getContent().get("message"));
        assertThat(failedMessage)
                .contains("基线分支不存在")
                .contains("CloudPlayerBaby/test01")
                .contains("develop")
                .contains("重试");
    }

    @Test
    void initializationFailureDoesNotFakeTaskRun() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        doThrow(new RuntimeException("sandbox worker down"))
                .when(fixture.sessions).acquire(any(), any(), any());

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess()))
                .orchestrate(task.getProjectId(), task.getId());

        verifyNoInteractions(fixture.taskRuns);
        verify(fixture.materialization, never()).materialize(any(), any());
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
        // Planner 重试两轮各创建一次 Run，随后 Developer/Tester/Reviewer 三个正式 Run。
        verify(fixture.taskRuns, times(5)).createForStep(eq(task.getProjectId()), eq(task.getId()), any(),
                anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(fixture.feedbacksFor(OrchestrationPhase.PLAN)).hasSize(2);
        assertThat(fixture.feedbacksFor(OrchestrationPhase.PLAN).get(0)).isNull();
        assertThat(fixture.feedbacksFor(OrchestrationPhase.PLAN).get(1).getOutcome())
                .isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test
    void reviewQualityFeedbackOnlyTargetsRepairCodingAndOriginalReviewUntilItPasses() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setIssue("missing ownership check");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING))).orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.feedbacksFor(OrchestrationPhase.CODING)).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksFor(OrchestrationPhase.TESTING)).containsExactly(null, null);
        assertThat(fixture.feedbacksFor(OrchestrationPhase.REVIEWING)).containsExactly(null, failedReview);
        ArgumentCaptor<String> reviewRunMessage = ArgumentCaptor.forClass(String.class);
        verify(fixture.taskRuns, atLeastOnce()).complete(any(), eq("FAILED"), isNull(), reviewRunMessage.capture());
        assertThat(reviewRunMessage.getAllValues()).anySatisfy(message ->
                assertThat(message).contains("已安排第1/3次质量修复"));
    }

    @Test
    void reviewQualityFailureResetsPreviouslySuccessfulTesterForRevalidation() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        failedReview.setReviewResult(reviewResult);
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.captureStepStatuses()).contains(developer.getId() + "=PENDING", tester.getId() + "=PENDING",
                reviewer.getId() + "=PENDING");
    }

    @Test
    void reviewQualityFeedbackTargetsLastMutableCustomStep() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity customWriter = fixture.step(task, "SECURITY", 2);
        customWriter.setExecutionMode("MUTATE");
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 3);
        fixture.stubPlan(task, planner, List.of(planner, customWriter, reviewer));
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.feedbacksForStep(customWriter.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(reviewer.getId())).containsExactly(null, failedReview);
    }

    @Test
    void formalPhaseInfrastructureRetryReceivesPreviousFailure() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedCoding = fixture.outcome(OrchestrationPhase.CODING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        failedCoding.setFailureCode("LLM_CONTEXT_LIMIT");

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), failedCoding,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING))).orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.feedbacksFor(OrchestrationPhase.CODING)).containsExactly(null, failedCoding);
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
    }

    @Test
    void testingEnvironmentFailureRetriesSamePhaseWithoutQualityLoop() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_INFRASTRUCTURE);
        failedTest.setFailureCode("TEST_SERVICE_UNAVAILABLE");

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                failedTest, fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        // 环境类测试失败：TESTING 同相位重试，不回到 CODING、不占用质量修复循环。
        assertThat(fixture.feedbacksFor(OrchestrationPhase.TESTING)).containsExactly(null, failedTest);
        assertThat(fixture.feedbacksFor(OrchestrationPhase.CODING)).containsExactly((AgentRunOutcome) null);
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(tester.getId()), anyString(), any(), any(), any());
    }

    @Test
    void environmentBlockedReviewQualityFailureIsReleasedWithoutRequeueingCoding() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome environmentBlockedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.TEST_FAILED);
        TestResult blockedTest = new TestResult();
        blockedTest.setSuccess(false);
        blockedTest.setEnvironmentFailureCode("TEST_DEPENDENCY_UNAVAILABLE");
        blockedTest.setSummary("测试因环境问题未能完成验证");
        environmentBlockedTest.setTestResult(blockedTest);

        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("BLOCKER");
        finding.setIssue("missing ownership check");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                environmentBlockedTest, failedReview)).orchestrate(task.getProjectId(), task.getId());

        // 环境失败转 Review 兜底：Review 判代码有疑点时不回 Coding（环境问题不是本次代码可修复，
        // 回修只会空转），按"能放行就放行"放行——Review 发现的 BLOCKER 仍完整落库，终态卡片如实
        // 标注测试未执行与放行原因；DIFF_FIRST 走待确认 Diff 由用户把关。
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(task.getFailureCode()).isNull();
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                "测试因环境问题未执行（TEST_DEPENDENCY_UNAVAILABLE），审查发现的问题已记录，已放行"
                        .equals(summary.get("bypassReason")));
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
    }

    @Test
    void reviewInfraFailureExhaustedIsReleasedWithPendingDiff() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome infraReview = fixture.outcome(OrchestrationPhase.REVIEWING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        infraReview.setMessage("review agent failed: git diff unavailable");
        // maxInfraRetries=3：4 次 FAILED_INFRASTRUCTURE（1 次初始 + 3 次重试）后重试耗尽判失败，
        // 编排器按"能放行就放行"诚实放行——审查未完成如实标注，绝不伪装成审查通过。
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), infraReview, infraReview, infraReview, infraReview);
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // DIFF_FIRST：放行后照常生成待确认 Diff 由用户把关，任务不失败。
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(task.getFailureCode()).isNull();
        verify(fixture.diffs, times(1)).createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("审查未完成"));
    }

    @Test
    void reviewInfraFailureExhaustedOnMrFirstReleasesWithoutAutoDelivery() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome infraReview = fixture.outcome(OrchestrationPhase.REVIEWING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        infraReview.setMessage("review agent failed: git diff unavailable");
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), infraReview, infraReview, infraReview, infraReview);
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // MR_FIRST + 审查未完成（重试耗尽）：任务 SUCCEEDED 但不得自动 commit/push/建 PR——
        // 自动交付必须有真实审查闸门，代码留工作区、卡片标注人工处理。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("DELIVERING");
        verify(fixture.diffs, never()).createSystemAcceptedBatch(any(), any(), any());
        verify(fixture.diffs, never()).createPendingBatch(any(), any(), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("审查未完成"));
    }

    @Test
    void testingInfraFailureExhaustedIsReleasedWithPendingDiff() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        // TESTING 是最后一个步骤：测试基础设施失败放行后无后续 REVIEW 步骤，直接进入交付终态。
        fixture.stubPlan(task, planner, List.of(planner, developer, tester));

        AgentRunOutcome infraTest = fixture.outcome(OrchestrationPhase.TESTING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        infraTest.setMessage("test agent failed: environment unavailable");
        // maxInfraRetries=3：4 次 FAILED_INFRASTRUCTURE（1 次初始 + 3 次重试）后重试耗尽判失败，
        // 编排器按"能放行就放行"诚实放行——测试未执行如实标注，绝不伪装成测试通过。
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                infraTest, infraTest, infraTest, infraTest);
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // DIFF_FIRST：放行后照常生成待确认 Diff 由用户把关，任务不失败。
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(task.getFailureCode()).isNull();
        verify(fixture.diffs, times(1)).createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("测试未执行"));
    }

    @Test
    void testingInfraFailureExhaustedOnMrFirstReleasesWithoutAutoDelivery() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester));

        AgentRunOutcome infraTest = fixture.outcome(OrchestrationPhase.TESTING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        infraTest.setMessage("test agent failed: environment unavailable");
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                infraTest, infraTest, infraTest, infraTest);
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // MR_FIRST + 测试未执行（重试耗尽）：任务 SUCCEEDED 但不得自动 commit/push/建 PR——
        // 自动交付必须有真实测试证据，代码留工作区、卡片标注人工处理。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("DELIVERING");
        verify(fixture.diffs, never()).createSystemAcceptedBatch(any(), any(), any());
        verify(fixture.diffs, never()).createPendingBatch(any(), any(), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("测试未执行"));
    }

    @Test
    void testingInfraFailureExhaustedThenReviewPassesWithPendingDiff() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome infraTest = fixture.outcome(OrchestrationPhase.TESTING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        infraTest.setMessage("test agent failed: environment unavailable");
        // TESTING 基础设施耗尽放行后，计划后续仍有 REVIEW 步骤：success 被 hasFollowingStep 降级为
        // advance，并按「Test 不判任务失败、Review 是最终裁决」路由到 review 节点兜底审查。
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                infraTest, infraTest, infraTest, infraTest, fixture.success(OrchestrationPhase.REVIEWING));
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // Review 兜底审查通过 → DIFF_FIRST 照常交付；「测试未执行」放行原因仍在终态如实标注。
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(task.getFailureCode()).isNull();
        verify(fixture.diffs, times(1)).createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("测试未执行"));
    }

    @Test
    void testingInfraRecoveredAfterReworkReenablesMrFirstAutoDelivery() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome infraTest = fixture.outcome(OrchestrationPhase.TESTING,
                RunOutcome.FAILED_INFRASTRUCTURE);
        infraTest.setMessage("test agent failed: environment unavailable");
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setIssue("missing error handling");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);
        // 基础设施恢复：Review 判可修 → Coding 修复 → 再测真实成功 → Review 通过。
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                infraTest, infraTest, infraTest, infraTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING));
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // 最后的 TESTING 真实执行成功已清除「测试未执行」放行标记，MR_FIRST 恢复自动交付：
        // 若标记未被清除，completeSuccess 会误判放行而返回 SUCCEEDED 不交付。
        assertThat(fixture.updatedStatuses()).contains("DELIVERING");
        verify(fixture.diffs, times(1)).createSystemAcceptedBatch(eq(task.getProjectId()), eq(task.getId()), any());
        verify(fixture.diffs, never()).createPendingBatch(any(), any(), any());
    }

    @Test
    void codingInfraFailureExhaustedReleasesWithSucceededStatus() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome infraCoding = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED_INFRASTRUCTURE);
        infraCoding.setMessage("developer agent failed: worker unavailable");
        // maxInfraRetries=3：4 次 FAILED_INFRASTRUCTURE 后重试耗尽，编排器按"能放行就放行"
        // 直接以 SUCCEEDED 结束——开发未完成如实标注，绝不伪装成开发完成。
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), infraCoding, infraCoding, infraCoding, infraCoding);
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // 开发未完成没有代码可验证：不得 advance 到后续 Test/Review（tester/reviewer 不建 Run），
        // completeSuccess 直接以 SUCCEEDED 结束，不生成待确认 Diff 批次（没有代码可确认）。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("WAITING_DIFF_CONFIRMATION", "DELIVERING");
        assertThat(task.getFailureCode()).isNull();
        verify(fixture.taskRuns, never()).createForStep(any(), any(), eq(tester.getId()), any(), any(), any(), any());
        verify(fixture.taskRuns, never()).createForStep(any(), any(), eq(reviewer.getId()), any(), any(), any(), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("开发未完成"));
    }

    @Test
    void codingInfraFailureExhaustedOnMrFirstReleasesWithoutAutoDelivery() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome infraCoding = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED_INFRASTRUCTURE);
        infraCoding.setMessage("developer agent failed: worker unavailable");
        Agent agent = fixture.sequenceAgent(fixture.planSuccess(), infraCoding, infraCoding, infraCoding, infraCoding);
        fixture.orchestrator(agent).orchestrate(task.getProjectId(), task.getId());

        // MR_FIRST + 开发未完成（重试耗尽）：任务 SUCCEEDED 但不得自动 commit/push/建 PR——
        // 自动交付必须有真实开发产出，代码留工作区、卡片标注人工处理。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("DELIVERING");
        verify(fixture.diffs, never()).createSystemAcceptedBatch(any(), any(), any());
        verify(fixture.diffs, never()).createPendingBatch(any(), any(), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("开发未完成"));
    }

    @Test
    void environmentBlockedReviewBlockerOnMrFirstReleasesWithoutAutoDelivery() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome environmentBlockedTest = fixture.outcome(OrchestrationPhase.TESTING,
                RunOutcome.TEST_FAILED);
        TestResult blockedTest = new TestResult();
        blockedTest.setSuccess(false);
        blockedTest.setEnvironmentFailureCode("TEST_SERVICE_UNAVAILABLE");
        blockedTest.setSummary("测试因环境问题未能完成验证");
        environmentBlockedTest.setTestResult(blockedTest);

        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("BLOCKER");
        finding.setIssue("missing ownership check");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                environmentBlockedTest, failedReview)).orchestrate(task.getProjectId(), task.getId());

        // MR_FIRST + 环境阻塞 + BLOCKER：用户已确认"也放行"，但 BLOCKER 不自动 push——
        // 安全由"不自动交付"兜底（SUCCEEDED 终态 + 卡片标注人工处理），findings 仍完整落库。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("DELIVERING");
        verify(fixture.diffs, never()).createSystemAcceptedBatch(any(), any(), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                "测试因环境问题未执行（TEST_SERVICE_UNAVAILABLE），审查发现的问题已记录，已放行"
                        .equals(summary.get("bypassReason")));
    }

    @Test
    void environmentBlockedReviewMajorOnMrFirstReleasesWithoutAutoDelivery() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome environmentBlockedTest = fixture.outcome(OrchestrationPhase.TESTING,
                RunOutcome.TEST_FAILED);
        TestResult blockedTest = new TestResult();
        blockedTest.setSuccess(false);
        blockedTest.setEnvironmentFailureCode("TEST_SERVICE_UNAVAILABLE");
        environmentBlockedTest.setTestResult(blockedTest);

        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setIssue("missing error handling");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                environmentBlockedTest, failedReview)).orchestrate(task.getProjectId(), task.getId());

        // MR_FIRST + 环境阻塞 + 仅 MAJOR（无 BLOCKER）：同样放行但不自动交付。
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("DELIVERING");
        verify(fixture.diffs, never()).createSystemAcceptedBatch(any(), any(), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                "测试因环境问题未执行（TEST_SERVICE_UNAVAILABLE），审查发现的问题已记录，已放行"
                        .equals(summary.get("bypassReason")));
    }

    @Test
    void environmentBlockedReviewPassReleasesTaskWithTestNotExecuted() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));

        AgentRunOutcome environmentBlockedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.TEST_FAILED);
        TestResult blockedTest = new TestResult();
        blockedTest.setSuccess(false);
        blockedTest.setEnvironmentFailureCode("TEST_SERVICE_UNAVAILABLE");
        blockedTest.setSummary("测试因环境问题未能完成验证");
        environmentBlockedTest.setTestResult(blockedTest);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), fixture.success(OrchestrationPhase.CODING),
                environmentBlockedTest, fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        // 环境失败转 Review：Review 判代码无误时放行（终态卡片标注测试未执行），任务继续交付。
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
    }

    @Test
    void codingSelfReportFailureWithRealChangesRetriesOnce() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedCoding = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED);
        failedCoding.setHasRealChanges(true);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), failedCoding,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING))).orchestrate(task.getProjectId(), task.getId());

        // 有真实写入证据的自报失败：自动同相位重试一次，重试 run 收到前一轮失败反馈。
        assertThat(fixture.feedbacksFor(OrchestrationPhase.CODING)).containsExactly(null, failedCoding);
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
    }

    @Test
    void codingSelfReportFailureWithoutRealChangesFailsTaskImmediately() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        fixture.stubPlan(task, planner, List.of(planner, developer));
        AgentRunOutcome failedCoding = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED);
        failedCoding.setHasRealChanges(false);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), failedCoding))
                .orchestrate(task.getProjectId(), task.getId());

        // 无任何真实写入的自报失败保持立即终态，不重试，避免 no-op 回环。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("FAILED");
    }

    @Test
    void codingSelfReportFailureExhaustsRetryBudgetThenFailsTask() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester));
        AgentRunOutcome first = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED);
        first.setHasRealChanges(true);
        AgentRunOutcome second = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED);
        second.setHasRealChanges(true);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), first, second))
                .orchestrate(task.getProjectId(), task.getId());

        // 重试预算 1 次：第一次自报失败重试，第二次不再重试，直接 Task FAILED。
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("FAILED");
    }

    @Test
    void codedCodingFailureWithRealChangesDoesNotTriggerSelfReportRetry() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        fixture.stubPlan(task, planner, List.of(planner, developer));
        AgentRunOutcome coded = fixture.outcome(OrchestrationPhase.CODING, RunOutcome.FAILED);
        coded.setFailureCode("TOOL_PATCH_UNRECOVERABLE");
        coded.setHasRealChanges(true);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(), coded))
                .orchestrate(task.getProjectId(), task.getId());

        // 已归类失败（补丁不可恢复）即使有真实写入也不走自报失败重试，保持原语义。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("FAILED");
    }

    @Test
    void testQualityFeedbackClearsOnlyAfterReviewingPasses() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        fixture.stubPlan(task, planner, List.of(planner, developer, tester, reviewer));
        AgentRunOutcome failedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);

        // TESTING FAILED_QUALITY 先记 feedback（Test 不自判），REVIEWING FAILED_QUALITY 覆盖为
        // review 源并触发 requeue；直到 REVIEWING SUCCEEDED 才清除（source=reviewer）。
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING))).orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.feedbacksFor(OrchestrationPhase.CODING)).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksFor(OrchestrationPhase.TESTING)).containsExactly(null, null);
        assertThat(fixture.feedbacksFor(OrchestrationPhase.REVIEWING)).containsExactly(null, failedReview);
    }

    @Test
    void qualityFeedbackDoesNotLeakAcrossMultipleTestingOrReviewingSteps() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity testSource = fixture.step(task, "TESTER", 3);
        TaskStepEntity otherTest = fixture.step(task, "TESTER", 4);
        TaskStepEntity reviewSource = fixture.step(task, "REVIEWER", 5);
        TaskStepEntity otherReview = fixture.step(task, "REVIEWER", 6);
        fixture.stubPlan(task, planner, List.of(planner, developer, testSource, otherTest, reviewSource, otherReview));
        AgentRunOutcome failedTest = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY);
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);

        // 线性链：developer → testSource → otherTest → reviewSource → otherReview。
        // TESTING FAILED_QUALITY 沿 review 边直接到下一个 REVIEW 步骤 reviewSource（跳过
        // otherTest——验证失败后不继续执行后续步骤）；REVIEWING FAILED_QUALITY 触发 requeue 回
        // developer；requeue 后 testSource 成功再沿 next 到 otherTest。REVIEWING SUCCEEDED 若还有
        // 后续 REVIEWER step 会继续推进（COMPLETE_SUCCESS + hasFollowingStep → advance）。
        // qualityFeedback 只对 source step 与 repair(developer) 可见，不泄漏到 otherTest/otherReview；
        // REVIEWING SUCCEEDED 时清除。
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), failedTest, failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.feedbacksForStep(developer.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(testSource.getId())).containsExactly(null, null);
        assertThat(fixture.feedbacksForStep(otherTest.getId())).containsExactly((AgentRunOutcome) null);
        assertThat(fixture.feedbacksForStep(reviewSource.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(otherReview.getId())).containsExactly((AgentRunOutcome) null);
    }

    @Test
    void testingPhaseFailureRoutesToNextReviewStepSkippingIntermediateSteps() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity verify = fixture.step(task, "DEVELOPER", 2);
        verify.setExecutionMode("VERIFY");
        TaskStepEntity mutate = fixture.step(task, "DEVELOPER", 3);
        mutate.setExecutionMode("MUTATE");
        TaskStepEntity tester = fixture.step(task, "TESTER", 4);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 5);
        List<TaskStepEntity> all = List.of(planner, verify, mutate, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        // 用户案例结构 [VERIFY, MUTATE, TEST, REVIEW]：VERIFY（TESTING 相位）失败后必须交
        // REVIEW 裁决，而不是按序列 next 先执行 MUTATE 写步骤；REVIEW 判质量失败后再 requeue
        // 回 MUTATE 修复，随后 TEST/REVIEW 成功完成任务。
        AgentRunOutcome failedVerify = fixture.outcome(OrchestrationPhase.TESTING, RunOutcome.TEST_FAILED);
        TestResult verifyResult = new TestResult();
        verifyResult.setSuccess(false);
        verifyResult.setNeedsCodingFix(true);
        failedVerify.setTestResult(verifyResult);
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                failedVerify, failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        // VERIFY 失败 → review 边直达 REVIEWER（跳过 MUTATE/TEST），REVIEW 裁决失败后 requeue
        // 回 MUTATE：MUTATE/TEST 各执行 1 次，REVIEWER 执行 2 次（先裁决失败、后确认通过）。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(mutate.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(tester.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(reviewer.getId()), anyString(), any(), any(), any());
        // REVIEW 裁决（source step）先收到自己的失败，MUTATE（repair step）收到同一份失败反馈。
        assertThat(fixture.feedbacksForStep(reviewer.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(mutate.getId())).containsExactly(failedReview);
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
        verify(fixture.messages, atLeastOnce()).upsertTaskStatusCard(eq(task.getRequirementGroupId()), any(), cards.capture());
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
        // 认领失败后不再进入正式图：仅 Planner Run 与 PLAN 产物已落库，不创建 Developer 等正式 Run。
        verify(fixture.taskRuns, times(1)).createForStep(eq(task.getProjectId()), eq(task.getId()), eq(planner.getId()),
                anyString(), any(), any(), any());
        verify(fixture.taskRuns).complete(any(), eq("SUCCEEDED"));
        verify(fixture.artifacts, times(1)).createRunArtifact(any(), any(), any(), eq("PLAN"), any());
        verify(fixture.artifacts, never()).createRunArtifact(any(), any(), any(), eq("CODING"), any());
    }

    @Test
    void agentRunHittingPhaseTimeoutIsBoundedAndReleased() throws Exception {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        // CODING 相位 agent.run() 永久阻塞（模拟 Worker HTTP 挂起），必须被总时限兜住（有界终止）。
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        Agent agent = input -> {
            if (call.getAndIncrement() == 0) {
                return fixture.planSuccess();
            }
            entered.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("dead code");
        };
        OrchestrationTimeoutProperties timeout = new OrchestrationTimeoutProperties();
        timeout.setCodingTimeout(java.time.Duration.ofMillis(200));
        timeout.setTestingTimeout(java.time.Duration.ofMillis(200));
        timeout.setReviewingTimeout(java.time.Duration.ofMillis(200));

        // 用独立线程驱动编排，避免阻塞的 agent 占住编排主线程导致整个测试挂死。
        java.util.concurrent.ExecutorService driver = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> orchestrate = driver.submit(() ->
                fixture.orchestrator(agent, timeout).orchestrate(task.getProjectId(), task.getId()));
        assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        orchestrate.get(5, java.util.concurrent.TimeUnit.SECONDS);

        ArgumentCaptor<Map> summary = ArgumentCaptor.forClass(Map.class);
        verify(fixture.artifacts, atLeastOnce()).createRunArtifact(any(), any(), any(), eq("CODING"), summary.capture());
        assertThat(summary.getAllValues()).anySatisfy(s ->
                assertThat(s.get("failureCode")).isEqualTo("AGENT_RUN_TIMEOUT"));
        InOrder persistedBeforeTerminal = inOrder(fixture.failureDiagnostics, fixture.artifacts, fixture.taskRuns);
        persistedBeforeTerminal.verify(fixture.failureDiagnostics, atLeastOnce()).record(eq(task), any(), eq(developer),
                eq(OrchestrationPhase.CODING), any());
        persistedBeforeTerminal.verify(fixture.artifacts, atLeastOnce()).createRunArtifact(eq(task), any(),
                eq(developer), eq("CODING"), any());
        // run 仍如实落 FAILED + AGENT_RUN_TIMEOUT（超时事实不隐藏）；重试耗尽后 task 按基础设施
        // 放行以 SUCCEEDED 结束并如实标注"开发未完成"（见 runStepNode 的 CODING 放行守卫）。
        persistedBeforeTerminal.verify(fixture.taskRuns, atLeastOnce()).complete(any(), eq("FAILED"),
                eq("AGENT_RUN_TIMEOUT"), any());
        assertThat(fixture.updatedStatuses()).contains("SUCCEEDED");
        assertThat(fixture.updatedStatuses()).doesNotContain("FAILED");
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(captured ->
                String.valueOf(captured.get("bypassReason")).contains("开发未完成"));
        driver.shutdownNow();
    }

    @Test
    void resumingFailedTaskClearsTaskFailedNotification() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        task.setStatus("FAILED");
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        List<TaskStepEntity> all = List.of(planner, developer);
        fixture.stubPlan(task, planner, all);
        // 从失败步骤续跑：认领（claimForResume，FAILED→RUNNING）成功后应撤销 TASK_FAILED 通知。
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING)))
                .orchestrate(task.getProjectId(), task.getId(), developer.getId());

        verify(fixture.notifications).clearTaskFailedNotifications(task.getId().toString());
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

    @Test
    void identicalReviewQualityFailureTwiceDeliversDiffInsteadOfFailing() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile("src/AuthService.java");
        finding.setIssue("missing ownership check");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        // 同一 MAJOR 连续两轮出现：第二轮判定不收敛，提前终止，不再空转第三次；但改为交付
        // 当前代码供用户人工核对（DIFF_FIRST 生成待确认 Diff），不再以任务 FAILED 收场。
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING), failedReview))
                .orchestrate(task.getProjectId(), task.getId());

        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(developer.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(tester.getId()), anyString(), any(), any(), any());
        verify(fixture.taskRuns, times(2)).createForStep(eq(task.getProjectId()), eq(task.getId()),
                eq(reviewer.getId()), anyString(), any(), any(), any());
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
        assertThat(fixture.updatedStatuses()).doesNotContain("FAILED");
        assertThat(task.getFailureCode()).isNull();
        verify(fixture.diffs, times(1)).createPendingBatch(eq(task.getProjectId()), eq(task.getId()), any());
        assertThat(fixture.capturedArtifactSummaries()).anyMatch(summary ->
                String.valueOf(summary.get("bypassReason")).contains("未见修复进展"));
    }

    @Test
    void manualRetryRehydratesPreviousReviewQualityFeedback() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity developer = fixture.step(task, "DEVELOPER", 2);
        TaskStepEntity tester = fixture.step(task, "TESTER", 3);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 4);
        List<TaskStepEntity> all = List.of(planner, developer, tester, reviewer);
        fixture.stubPlan(task, planner, all);

        // 用户重试的正是上一次 FAILED_QUALITY 审查运行：新编排会话应认领该 retry（claimForRetry）。
        UUID retriedRunId = UUID.randomUUID();
        TaskRunEntity retriedRun = new TaskRunEntity();
        retriedRun.setId(retriedRunId);
        retriedRun.setRole("REVIEWER");
        retriedRun.setTaskStepId(reviewer.getId());
        when(fixture.taskRuns.findById(retriedRunId)).thenReturn(retriedRun);
        when(fixture.tasks.claimForRetry(eq(task.getProjectId()), eq(task.getId()), eq(retriedRunId))).thenReturn(1);
        // 该审查运行自身的 REVIEWING 产物带完整 findings。
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskStepId(reviewer.getId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", "FAILED_QUALITY");
        summary.put("review", Map.of("success", false, "needsCodingFix", true,
                "findings", List.of(Map.of("severity", "MAJOR", "file", "src/AuthService.java",
                        "line", 3, "issue", "missing ownership check"))));
        artifact.setSummary(summary);
        when(fixture.artifacts.latestFailedQualityReviewingArtifact(task.getId(), retriedRunId)).thenReturn(artifact);

        // 新编排会话从 REVIEWER 步骤续跑；重水合后该步骤的 assemble 应拿到前一轮 FAILED_QUALITY
        // 反馈（含重建的 findings，且不携带误分类 failureCode，避免带偏修复方向）。
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId(), reviewer.getId(), retriedRunId);

        ArgumentCaptor<AgentRunOutcome> feedback = ArgumentCaptor.forClass(AgentRunOutcome.class);
        verify(fixture.context).assemble(eq(task), eq(reviewer), eq(OrchestrationPhase.REVIEWING),
                feedback.capture(), any(), any(), any(), any(), any(), any());
        AgentRunOutcome rehydrated = feedback.getValue();
        assertThat(rehydrated).isNotNull();
        assertThat(rehydrated.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(rehydrated.getPhase()).isEqualTo(OrchestrationPhase.REVIEWING);
        assertThat(rehydrated.getFailureCode()).isNull();
        assertThat(rehydrated.getReviewResult()).isNotNull();
        assertThat(rehydrated.getReviewResult().isNeedsCodingFix()).isTrue();
        assertThat(rehydrated.getReviewResult().getFindings()).hasSize(1);
        assertThat(rehydrated.getReviewResult().getFindings().get(0).getSeverity()).isEqualTo("MAJOR");
        assertThat(rehydrated.getReviewResult().getFindings().get(0).getFile()).isEqualTo("src/AuthService.java");
    }

    @Test
    void qualityFailureRoutesRepairToStepMatchingFindingRepoInsteadOfLastMutable() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity backend = fixture.step(task, "DEVELOPER", 2);
        backend.setExecutionMode("MUTATE");
        backend.setTargetFiles(List.of("repo-3/src/main/java/AuthController.java",
                "repo-3/src/main/java/AuthService.java"));
        TaskStepEntity frontend = fixture.step(task, "DEVELOPER", 3);
        frontend.setExecutionMode("MUTATE");
        frontend.setTargetFiles(List.of("repo-2/src/RegisterScreen.js"));
        TaskStepEntity tester = fixture.step(task, "TESTER", 4);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 5);
        List<TaskStepEntity> all = List.of(planner, backend, frontend, tester, reviewer);
        fixture.stubPlan(task, planner, all);

        // 审查 findings 指向 repo-3 后端缺陷，而最后一个 MUTATE 步骤（frontend）只声明 repo-2 文件。
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile("repo-3/src/main/java/AuthService.java");
        finding.setIssue("password stored in plaintext");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        // 修复应路由到 target_files 覆盖 repo-3 的 backend 步骤（而非最后一个 MUTATE=frontend），
        // 使修复指令与 target_files 同审查问题所在的仓库，避免质量循环在错误仓库空转。
        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        // backend（命中 findings 仓库）收到修复反馈；frontend 重跑但不携带修复反馈。
        assertThat(fixture.feedbacksForStep(backend.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(frontend.getId())).containsExactly(null, null);
        // 路由到更早的 backend 步骤时，resetStepsForQualityRework 从该步骤重置，后续 MUTATE 一并重跑。
        assertThat(fixture.captureStepStatuses()).contains(backend.getId() + "=PENDING",
                frontend.getId() + "=PENDING");
        assertThat(fixture.updatedStatuses()).contains("WAITING_DIFF_CONFIRMATION");
    }

    @Test
    void qualityFailureFallsBackToLastMutableWhenFindingsDoNotMatchAnyStepRepo() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity backend = fixture.step(task, "DEVELOPER", 2);
        backend.setExecutionMode("MUTATE");
        backend.setTargetFiles(List.of("repo-3/src/main/java/AuthService.java"));
        TaskStepEntity frontend = fixture.step(task, "DEVELOPER", 3);
        frontend.setExecutionMode("MUTATE");
        frontend.setTargetFiles(List.of("repo-2/src/RegisterScreen.js"));
        TaskStepEntity tester = fixture.step(task, "TESTER", 4);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 5);
        List<TaskStepEntity> all = List.of(planner, backend, frontend, tester, reviewer);
        fixture.stubPlan(task, planner, all);

        // findings 归属 repo-5，不在任何 MUTATE 步骤的仓库范围内：无步骤可定向，回退最后一个 MUTATE。
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile("repo-5/src/External.java");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        // frontend（最后一个 MUTATE）收到修复反馈；backend 不重置、不携带修复反馈。
        assertThat(fixture.feedbacksForStep(frontend.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(backend.getId())).containsExactly((AgentRunOutcome) null);
        assertThat(fixture.captureStepStatuses()).doesNotContain(backend.getId() + "=PENDING")
                .contains(frontend.getId() + "=PENDING");
    }

    @Test
    void qualityFailureRoutesToLastMutableWhenAllStepsShareOneRepository() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task();
        TaskStepEntity planner = fixture.step(task, "PLANNER", 1);
        TaskStepEntity backend = fixture.step(task, "DEVELOPER", 2);
        backend.setExecutionMode("MUTATE");
        backend.setTargetFiles(List.of("repo-1/src/main/java/AuthService.java"));
        TaskStepEntity frontend = fixture.step(task, "DEVELOPER", 3);
        frontend.setExecutionMode("MUTATE");
        frontend.setTargetFiles(List.of("repo-1/src/RegisterScreen.js"));
        TaskStepEntity tester = fixture.step(task, "TESTER", 4);
        TaskStepEntity reviewer = fixture.step(task, "REVIEWER", 5);
        List<TaskStepEntity> all = List.of(planner, backend, frontend, tester, reviewer);
        fixture.stubPlan(task, planner, all);

        // 所有 MUTATE 步骤同属 repo-1（单仓库任务）：仓库路由无意义，维持最后一个 MUTATE 语义。
        AgentRunOutcome failedReview = fixture.outcome(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY);
        ReviewResult reviewResult = new ReviewResult();
        reviewResult.setSuccess(false);
        reviewResult.setNeedsCodingFix(true);
        ReviewResult.Finding finding = new ReviewResult.Finding();
        finding.setSeverity("MAJOR");
        finding.setFile("repo-1/src/main/java/AuthService.java");
        reviewResult.setFindings(List.of(finding));
        failedReview.setReviewResult(reviewResult);

        fixture.orchestrator(fixture.sequenceAgent(fixture.planSuccess(),
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.CODING),
                fixture.success(OrchestrationPhase.TESTING), failedReview,
                fixture.success(OrchestrationPhase.CODING), fixture.success(OrchestrationPhase.TESTING),
                fixture.success(OrchestrationPhase.REVIEWING)))
                .orchestrate(task.getProjectId(), task.getId());

        assertThat(fixture.feedbacksForStep(frontend.getId())).containsExactly(null, failedReview);
        assertThat(fixture.feedbacksForStep(backend.getId())).containsExactly((AgentRunOutcome) null);
        assertThat(fixture.captureStepStatuses()).doesNotContain(backend.getId() + "=PENDING")
                .contains(frontend.getId() + "=PENDING");
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
        private final TaskRunFailureDiagnosticService failureDiagnostics = mock(TaskRunFailureDiagnosticService.class);
        private final DiffMapper diffMapper = mock(DiffMapper.class);
        private final MessageService messages = mock(MessageService.class);
        private final NotificationService notifications = mock(NotificationService.class);
        private final OrchestratorAgentService orchestratorAgents = mock(OrchestratorAgentService.class);
        private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
        private final java.util.concurrent.ExecutorService timeoutExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        private final ThreadLocal<Agent> currentAgent = new ThreadLocal<>();
        private final List<String> stepStatusHistory = new ArrayList<>();

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
            doAnswer(invocation -> {
                TaskStepEntity saved = invocation.getArgument(0);
                stepStatusHistory.add(saved.getId() + "=" + saved.getStatus());
                return 1;
            }).when(steps).updateById(any(TaskStepEntity.class));
            when(registry.resolve(any(), any())).thenAnswer(invocation -> Optional.of(currentAgent.get()));
            when(registry.resolve(any(), any(), any())).thenAnswer(invocation -> Optional.of(currentAgent.get()));
            when(context.buildGroupContext(task)).thenReturn(mock(GroupContext.class));
            when(taskRuns.createForStep(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                TaskRunEntity run = new TaskRunEntity();
                run.setId(UUID.randomUUID());
                return run;
            });
        }

        TaskOrchestrator orchestrator(Agent agent) {
            return orchestrator(agent, new OrchestrationTimeoutProperties());
        }

        TaskOrchestrator orchestrator(Agent agent, OrchestrationTimeoutProperties timeout) {
            currentAgent.set(agent);
            return new TaskOrchestrator(new OrchestrationStateMachine(), new WorkflowGraphBuilder(), registry, context,
                    taskRuns, tasks, steps, events,
                    notifications, sessions, artifacts, failureDiagnostics, diffs,
                    diffMapper, messages, orchestratorAgents,
                    materialization, timeoutExecutor, timeout);
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

        /** 步骤状态更新序列（按 updateById 调用顺序），供质量修复重置断言使用。 */
        List<String> captureStepStatuses() {
            return List.copyOf(stepStatusHistory);
        }

        List<AgentRunOutcome> feedbacksFor(OrchestrationPhase phase) {
            return mockingDetails(context).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("assemble"))
                    .filter(invocation -> phase == invocation.getArgument(2))
                    .map(invocation -> (AgentRunOutcome) invocation.getArgument(3))
                    .toList();
        }

        List<AgentRunOutcome> feedbacksForStep(UUID stepId) {
            return mockingDetails(context).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("assemble"))
                    .filter(invocation -> stepId.equals(((TaskStepEntity) invocation.getArgument(1)).getId()))
                    .map(invocation -> (AgentRunOutcome) invocation.getArgument(3))
                    .toList();
        }

        /** createRunArtifact 收到的全部产物摘要 Map，供断言放行原因等 Run 级记录。 */
        List<Map<String, Object>> capturedArtifactSummaries() {
            return mockingDetails(artifacts).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createRunArtifact"))
                    .map(invocation -> (Map<String, Object>) invocation.getArgument(4))
                    .toList();
        }
    }
}
