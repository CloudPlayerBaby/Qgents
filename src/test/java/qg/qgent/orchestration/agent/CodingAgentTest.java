package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.dto.ContextMessage;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.RetryContext;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;
import qg.qgent.service.ContextService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodingAgent 单元测试：默认协议（native）以 mock {@link LlmClient#nextToolTurn} 驱动原生
 * Tool Calling 循环，覆盖成功收敛、多轮工具循环、工具白名单、基础设施中止、错误码分类
 * （length 截断 / 上下文超限 / 参数非法）、观测落库；legacy 手写 JSON 协议（灰度期）以 mock
 * {@link LlmClient#complete} 做少量回归。工具的真实执行与类型校验由 CodingToolsTest 覆盖。
 * 不写入任何 API Key。
 */
class CodingAgentTest {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final AttachmentMediaLoader attachmentMediaLoader = mock(AttachmentMediaLoader.class);
    private final ContextService contextService = mock(ContextService.class);
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void stubDefaultEmptyAttachments() {
        when(attachmentMediaLoader.load(any(), any(), any()))
                .thenReturn(new AttachmentMediaLoader.Result(List.of(), ""));
    }

    private CodingAgent nativeAgent() {
        return new CodingAgent(llm, codeAccess, writer, AgentProtocol.nativeDefault(),
                contextService, new ContextSearchProperties(10), attachmentMediaLoader);
    }

    private CodingAgent legacyAgent() {
        return new CodingAgent(llm, codeAccess, writer, new AgentProtocol("legacy"),
                contextService, new ContextSearchProperties(10), attachmentMediaLoader);
    }

    // ---------- 原生 Tool Calling（默认协议） ----------

    @Test
    void nativeBareFinalResultWithoutActualWriteIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
        assertThat(outcome.getObservations()).hasSize(1);
        assertThat(outcome.getObservations().get(0).phase()).isEqualTo("CODING");
    }

    @Test
    void nativeWrappedFinalResultWithoutActualWriteIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":" + bareResult(true, "ok", "src/main/java/X.java") + "}", "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
    }

    @Test
    void nativeMultiRoundToolLoopExecutesToolsInOrder() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", "0".repeat(64), "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            int current = round.getAndIncrement();
            if (current == 0) {
                return toolTurn("read_file");
            }
            if (current == 1) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "apply_patch".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/main/java/X.java\",\"expectedHash\":\""
                                + "0".repeat(64) + "\",\"patch\":\"patch\"}");
                return toolTurn("apply_patch");
            }
            return finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(3)).nextToolTurn(anyString(), anyList(), anyList());
        assertThat(outcome.getObservations()).hasSize(3);
        assertThat(outcome.getObservations().get(0).toolName()).isEqualTo("read_file");
        assertThat(outcome.getObservations().get(1).toolName()).isEqualTo("apply_patch");
        assertThat(outcome.getObservations().get(2).protocolFailureCode()).isNull();
    }

    @Test
    void nativePassesWhitelistedCodingTools() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", null), "stop"));

        nativeAgent().run(codingInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).nextToolTurn(anyString(), anyList(), toolsCaptor.capture());
        List<String> names = toolsCaptor.getValue().stream()
                .map(c -> c.getToolDefinition().name()).sorted().toList();
        assertThat(names).containsExactly("activate_skill", "apply_patch", "create_directory",
                "ensure_trailing_newline", "list_files", "read_file",
                "replace_file", "search_chat_history", "search_code", "write_file");
    }

    @Test
    void nativeQualityRepairPreloadsReviewerActivatedSkillIntoDeveloperPrompt() {
        UUID skillId = UUID.randomUUID();
        qg.qgent.entity.SkillEntity skill = new qg.qgent.entity.SkillEntity();
        skill.setName("README 规范");
        skill.setContent("README 最后一行必须为 Hiiii113");
        AgentInput input = codingInput();
        RetryContext retry = new RetryContext();
        retry.setReviewActivatedSkillIds(List.of(skillId));
        input.setRetryContext(retry);
        when(contextService.activateSkill(input.getActorId(), input.getProjectId(), skillId)).thenReturn(skill);
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(false, "needs repair", null), "stop"));

        nativeAgent().run(input);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        String userMessage = ((UserMessage) historyCaptor.getValue().get(0)).getText();
        assertThat(userMessage).contains("质量回修必读 Skill", skillId.toString(), "README 最后一行必须为 Hiiii113");
        verify(contextService).activateSkill(input.getActorId(), input.getProjectId(), skillId);
    }

    @Test
    void nativeToolHistoryFlowsToNextCall() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurn("list_files"),
                        finalTurn(bareResult(true, "done", null), "stop"));

        nativeAgent().run(codingInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        // 第二轮历史 = continueTools 返回的历史（非空、含首轮 user 与工具执行结果），原样回传。
        assertThat(historyCaptor.getAllValues().get(1)).hasSize(1);
        assertThat(historyCaptor.getAllValues().get(1).get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void nativeInfraAbortMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(infraTurn("apply_patch", "workspace root is not available"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("workspace root is not available");
        verify(llm, times(1)).nextToolTurn(anyString(), anyList(), anyList());
    }

    @Test
    void nativeFinishLengthFinalizationWithoutActualWriteIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":true,\"summary\":\"tr", "LENGTH"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn(bareResult(true, "recovered", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
    }

    @Test
    void nativeMaxRoundsFinalizationWithoutActualWriteIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenReturn(toolTurn("list_files"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn(bareResult(true, "bounded finish", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
        verify(llm, times(MAX_TOOL_ROUNDS)).nextToolTurn(anyString(), anyList(), anyList());
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
        assertThat(outcome.getObservations()).hasSize(MAX_TOOL_ROUNDS + 1);
    }

    @Test
    void nativeTruncatedFinalizationKeepsOriginalLengthFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":", "LENGTH"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn("{\"finalResult\":", "LENGTH"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_FINISH_LENGTH.name());
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
    }

    @Test
    void nativeToolArgumentInvalidObservationIsRecorded() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurnWithCode("apply_patch", ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID),
                        finalTurn(bareResult(false, "corrected", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getObservations()).hasSize(2);
        assertThat(outcome.getObservations().get(0).protocolFailureCode())
                .isEqualTo(ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID);
        // 成功轮无错误码。
        assertThat(outcome.getObservations().get(1).protocolFailureCode()).isNull();
    }

    @Test
    void nativeLlmCallFailureMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("llm boom"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("llm boom");
    }

    @Test
    void nativeMalformedFinalTextMapsToToolCallMalformed() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("this is not json", "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
    }

    @Test
    void nativeMalformedJsonRepairWithoutActualWriteIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":true,\"summary\":\"将\"和\"字居中\"}", "stop"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"done\","
                        + "\"modifiedFiles\":[\"src/main/java/X.java\"]}}");

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
        assertThat(outcome.getObservations()).hasSize(2);
        verify(llm).complete(anyString(), anyList());
    }

    @Test
    void nativeDirectoryCreateCanBeTheOnlyObservedChange() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.createDirectory(workspaceId, "src/generated"))
                .thenReturn(WorkspaceDirectoryResult.ok("src/generated", true));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            if (round.getAndIncrement() == 0) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "create_directory".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/generated\"}");
                return toolTurn("create_directory");
            }
            return finalTurn(bareResult(true, "created directory", "fabricated/File.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getModifiedFiles()).isEmpty();
        assertThat(outcome.getCodingResult().getModifiedDirectories()).containsExactly("src/generated");
        verify(writer).createDirectory(workspaceId, "src/generated");
    }

    @Test
    void nativeIdempotentDirectoryCreateDoesNotSatisfyChangedWriteGate() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.createDirectory(workspaceId, "src/generated"))
                .thenReturn(WorkspaceDirectoryResult.ok("src/generated", false));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            if (round.getAndIncrement() == 0) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "create_directory".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/generated\"}");
                return toolTurn("create_directory");
            }
            return finalTurn(bareResult(true, "directory already exists", null), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
    }

    @Test
    void nativeSuccessWithoutWritesIsSatisfiedOnlyForQualityRepairWhenTargetsExistNonEmpty() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(codeAccess.readFile(any(), any())).thenReturn(
                WorkspaceFileReadResult.ok("src/main/java/X.java", "class X {}", "abc", true, "LF"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop"));
        AgentInput input = codingInput();
        input.setTargetFiles(List.of("src/main/java/X.java"));
        // 仅质量修复步骤允许零写入 satisfied 兜底；普通 MUTATE 步骤无写入必须失败。
        RetryContext retry = new RetryContext();
        retry.setQualityRepair(true);
        input.setRetryContext(retry);

        AgentRunOutcome outcome = nativeAgent().run(input);

        // 目标已被前序步骤满足：无实际写入也按 SUCCEEDED 收敛，且不把模型声称的路径回填为本次写入。
        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getModifiedFiles()).isEmpty();
        assertThat(outcome.getCodingResult().getModifiedDirectories()).isEmpty();
    }

    @Test
    void nativeSuccessWithoutWritesRejectedForNormalMutateEvenWhenTargetsExist() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(codeAccess.readFile(any(), any())).thenReturn(
                WorkspaceFileReadResult.ok("src/main/java/X.java", "class X {}", "abc", true, "LF"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop"));
        AgentInput input = codingInput();
        input.setTargetFiles(List.of("src/main/java/X.java"));
        // 普通 MUTATE 步骤（非质量修复）：即使目标存在且内容非空，零写入仍判 CODING_NO_ACTUAL_CHANGE。
        RetryContext retry = new RetryContext();
        retry.setQualityRepair(false);
        input.setRetryContext(retry);

        AgentRunOutcome outcome = nativeAgent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
    }

    @Test
    void nativeSuccessWithoutWritesStillRejectedWhenTargetMissing() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop"));
        AgentInput input = codingInput();
        input.setTargetFiles(List.of("src/main/java/X.java"));

        AgentRunOutcome outcome = nativeAgent().run(input);

        // 目标未在 Workspace 出现且零写入：仍判为真正的语义失败，不得放行。
        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
    }

    @Test
    void nativeZeroChangeFailureCarriesToolAttemptSummary() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", "0".repeat(64), "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            if (round.getAndIncrement() == 0) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "apply_patch".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/main/java/X.java\",\"expectedHash\":\""
                                + "0".repeat(64) + "\",\"patch\":\"patch\"}");
                return toolTurn("apply_patch");
            }
            return finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        // 零改动失败消息带出具体尝试汇总（工具、次数与原因），供 requeue 上下文诊断。
        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
        assertThat(outcome.getMessage())
                .contains("编码工具尝试汇总")
                .contains("apply_patch 共 1 次（失败 1 次）")
                .contains("hunk 声明行数与正文不一致");
    }

    @Test
    void repeatedPatchFailureBecomesUnrecoverableToolFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", "0".repeat(64), "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            if (round.getAndIncrement() < CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "apply_patch".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/main/java/X.java\",\"expectedHash\":\""
                                + "0".repeat(64) + "\",\"patch\":\"patch\"}");
                return toolTurn("apply_patch");
            }
            return finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.TOOL_PATCH_UNRECOVERABLE.name());
        assertThat(outcome.getMessage()).contains("replace_file");
    }

    @Test
    void repairedSuccessWithoutAnyModifiedFileIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("not json", "stop"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"未执行任何文件修改\"}}");

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE.name());
        assertThat(outcome.getMessage()).contains("requires at least one actual file or directory modification");
    }

    @Test
    void nativeMalformedJsonRepairFailureKeepsStableFailureCode() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("not json", "stop"));
        when(llm.complete(anyString(), anyList())).thenReturn("still not json");

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
        assertThat(outcome.getObservations()).hasSize(2);
    }

    // ---------- 多模态输入（IMAGE 媒体 + FILE 文本块） ----------

    @Test
    void nativeImageAttachmentAddsMediaToInitialUserMessage() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", "0".repeat(64), "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        UUID actorId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        AgentInput input = codingInput();
        input.setActorId(actorId);
        input.setConversation(List.of(new ContextMessage(1L, "IMAGE", "USER", actorId.toString(), "",
                attachmentId.toString(), "design.png", "image/png")));
        when(attachmentMediaLoader.load(any(), any(), anyList()))
                .thenReturn(new AttachmentMediaLoader.Result(
                        List.of(new org.springframework.ai.content.Media(
                                org.springframework.util.MimeType.valueOf("image/png"),
                                java.net.URI.create("data:image/png;base64,AQID"))), ""));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            int current = round.getAndIncrement();
            if (current == 0) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "apply_patch".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/main/java/X.java\",\"expectedHash\":\""
                                + "0".repeat(64) + "\",\"patch\":\"patch\"}");
                return toolTurn("apply_patch");
            }
            return finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        UserMessage first = (UserMessage) historyCaptor.getAllValues().get(0).get(0);
        assertThat(first.getMedia()).hasSize(1);
        assertThat(first.getMedia().get(0).getMimeType().toString()).isEqualTo("image/png");
    }

    @Test
    void nativeImageAttachmentUnavailableDegradesToTextReference() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", "0".repeat(64), "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        UUID actorId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        AgentInput input = codingInput();
        input.setActorId(actorId);
        input.setConversation(List.of(new ContextMessage(1L, "IMAGE", "USER", actorId.toString(), "",
                attachmentId.toString(), "design.png", "image/png")));
        when(attachmentMediaLoader.load(any(), any(), anyList()))
                .thenReturn(new AttachmentMediaLoader.Result(List.of(), ""));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            int current = round.getAndIncrement();
            if (current == 0) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "apply_patch".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/main/java/X.java\",\"expectedHash\":\""
                                + "0".repeat(64) + "\",\"patch\":\"patch\"}");
                return toolTurn("apply_patch");
            }
            return finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        UserMessage first = (UserMessage) historyCaptor.getAllValues().get(0).get(0);
        assertThat(first.getMedia()).isEmpty();
    }

    @Test
    void nativeFileTextContentIsAppendedToUserMessage() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", "0".repeat(64), "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        UUID actorId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        AgentInput input = codingInput();
        input.setActorId(actorId);
        input.setConversation(List.of(new ContextMessage(1L, "FILE", "USER", actorId.toString(), "",
                attachmentId.toString(), "requirements.txt", "text/plain")));
        when(attachmentMediaLoader.load(any(), any(), anyList()))
                .thenReturn(new AttachmentMediaLoader.Result(List.of(),
                        "\n\n[附件内容: requirements.txt]\n需要支持历史导出"));
        AtomicInteger round = new AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            int current = round.getAndIncrement();
            if (current == 0) {
                @SuppressWarnings("unchecked")
                List<ToolCallback> callbacks = invocation.getArgument(2);
                callbacks.stream()
                        .filter(callback -> "apply_patch".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow()
                        .call("{\"path\":\"src/main/java/X.java\",\"expectedHash\":\""
                                + "0".repeat(64) + "\",\"patch\":\"patch\"}");
                return toolTurn("apply_patch");
            }
            return finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop");
        });

        AgentRunOutcome outcome = nativeAgent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        UserMessage first = (UserMessage) historyCaptor.getAllValues().get(0).get(0);
        assertThat(first.getMedia()).isEmpty();
        assertThat(first.getText()).contains("[附件内容: requirements.txt]").contains("需要支持历史导出");
    }

    // ---------- legacy 手写 JSON 协议（灰度期回归） ----------

    @Test
    void legacyFinalResultSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/X.java", "code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"write_file\",\"arguments\":{\"path\":\"src/main/java/X.java\",\"content\":\"code\"}}}",
                        "{\"finalResult\":{\"success\":true,\"summary\":\"done\",\"modifiedFiles\":[\"src/main/java/X.java\"]}}");

        AgentRunOutcome outcome = legacyAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getModifiedFiles()).containsExactly("src/main/java/X.java");
    }

    @Test
    void legacyWriteFailsInfrastructureAndDoesNotFeedBack() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "code"))
                .thenReturn(WorkspaceWriteResult.infraFail("src/main/java/Y.java", "workspace root is not available"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"write_file\",\"arguments\":{\"path\":\"src/main/java/Y.java\",\"content\":\"code\"}}}");

        AgentRunOutcome outcome = legacyAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("write_file infrastructure failure");
        verify(llm, times(1)).complete(anyString(), anyList());
    }

    @Test
    void legacyExceedingMaxRoundsFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"list_files\"}}");

        AgentRunOutcome outcome = legacyAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_CONTEXT_LIMIT.name());
        verify(llm, times(MAX_TOOL_ROUNDS)).complete(anyString(), anyList());
    }

    // ---------- 工具与观测辅助 ----------

    private AgentInput codingInput() {
        AgentInput input = new AgentInput();
        input.setProjectId(UUID.randomUUID());
        input.setTaskId(UUID.randomUUID());
        input.setTaskTitle("sample coding task");
        input.setRequirement("implement a feature");
        input.setInstruction("implement per plan");
        input.setPhase(OrchestrationPhase.CODING);
        input.setWorkspaceId(workspaceId);
        PlanResult plan = new PlanResult();
        plan.setTaskUnderstanding("understand the task");
        plan.setObjectives(List.of("goal"));
        PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
        step.setTitle("impl");
        step.setFiles(List.of("src/main/java/X.java"));
        plan.setImplementationSteps(List.of(step));
        plan.setTestPlan("run tests");
        input.setPlanResult(plan);
        return input;
    }

    private ToolTurnResult finalTurn(String json, String finishReason) {
        return ToolTurnResult.finalAnswer(json, finishReason, 20, 10, "aabb", null);
    }

    private ToolTurnResult toolTurn(String toolName) {
        return ToolTurnResult.continueTools(List.of(new UserMessage("tool executed: " + toolName)),
                "stop", 20, 10, "aabb", toolName, null);
    }

    private ToolTurnResult toolTurnWithCode(String toolName, ProtocolFailureCode code) {
        return ToolTurnResult.continueTools(List.of(new UserMessage("tool executed: " + toolName)),
                "stop", 20, 10, "aabb", toolName, code);
    }

    private ToolTurnResult infraTurn(String toolName, String reason) {
        return ToolTurnResult.infraAbort(reason, "stop", 20, 10, "aabb", toolName);
    }

    private String bareResult(boolean success, String summary, String modifiedFile) {
        StringBuilder json = new StringBuilder("{\"success\":").append(success)
                .append(",\"summary\":\"").append(summary).append("\"");
        if (modifiedFile != null) {
            json.append(",\"modifiedFiles\":[\"").append(modifiedFile).append("\"]");
        }
        return json.append("}").toString();
    }
}
