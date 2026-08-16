package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.service.WorkspaceService;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.LocalWorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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
 * CodingAgent 纯单元测试：Mock LLM 驱动 JSON 工具调用协议，覆盖正常读写、多轮循环、
 * 工具结果回灌、持久化写入、路径穿越/越界拒绝、工具失败、LLM 失败、循环上限与
 * CodingResult 装配。真实写盘场景使用 {@code @TempDir} 指向本地最小实现
 * {@link LocalWorkspaceCodeWriter}，验证文件确实落在 Workspace 目录。不写入任何 API Key。
 */
class CodingAgentTest {

    private static final int MAX_TOOL_ROUNDS = 20;

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final UUID workspaceId = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CodingAgent agent() {
        return new CodingAgent(llm, codeAccess, writer);
    }

    @Test
    void readFileToolExecutesAndFinalResultSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(readTool("src/main/java/X.java"), finalResult(true, "done", "src/main/java/X.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().isSuccess()).isTrue();
        assertThat(outcome.getCodingResult().getModifiedFiles()).containsExactly("src/main/java/X.java");
        verify(codeAccess).readFile(workspaceId, "src/main/java/X.java");
    }

    @Test
    void writeFileToolWritesThroughWriter() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "new code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/Y.java"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(writeTool("src/main/java/Y.java", "new code"),
                        finalResult(true, "implemented", "src/main/java/Y.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(writer).writeFile(workspaceId, "src/main/java/Y.java", "new code");
    }

    @Test
    void multiRoundToolLoopExecutesToolsInOrder() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));
        when(writer.writeFile(any(), anyString(), anyString())).thenReturn(WorkspaceWriteResult.ok("src/main/java/Y.java"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(readTool("src/main/java/X.java"),
                        writeTool("src/main/java/Y.java", "code"),
                        finalResult(true, "done", "src/main/java/Y.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(3)).complete(anyString(), anyList());
        verify(codeAccess).readFile(workspaceId, "src/main/java/X.java");
        verify(writer).writeFile(workspaceId, "src/main/java/Y.java", "code");
    }

    @Test
    void toolResultIsFedBackIntoLlmHistory() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(readTool("src/main/java/X.java"), finalResult(true, "done", "src/main/java/X.java"));

        agent().run(codingInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), historyCaptor.capture());
        List<LlmMessage> secondHistory = historyCaptor.getAllValues().get(1);
        assertThat(secondHistory).anySatisfy(msg -> {
            assertThat(msg.role()).isEqualTo(LlmMessage.Role.TOOL);
            assertThat(msg.content()).contains("\"tool\":\"read_file\"", "\"content\":\"code\"");
        });
    }

    @Test
    void readFileResultIncludesSha256ForApplyPatch() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(readTool("src/main/java/X.java"), finalResult(true, "done", "src/main/java/X.java"));

        agent().run(codingInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"tool\":\"read_file\"", "\"sha256\":\"" + HASH + "\""));
    }

    @Test
    void applyPatchToolAppliesThroughWriter() {
        String patch = "@@ -1,1 +1,1 @@\n-code\n+new code\n";
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, patch))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(applyPatchTool("src/main/java/X.java", HASH, patch),
                        finalResult(true, "patched", "src/main/java/X.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(writer).patchFile(workspaceId, "src/main/java/X.java", HASH, patch);
    }

    @Test
    void applyPatchMissingHashIsToolErrorAndModelRecovers() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"apply_patch\",\"arguments\":"
                                + "{\"path\":\"src/main/java/X.java\",\"patch\":\"x\"}}}",
                        finalResult(true, "retried with read", "src/main/java/X.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(writer, never()).patchFile(any(), anyString(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"ok\":false"));
    }

    @Test
    void applyPatchHashMismatchIsToolErrorAndModelRetries() {
        String patch = "@@ -1,1 +1,1 @@\n-code\n+new code\n";
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, patch))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "file has changed since read"),
                        WorkspaceWriteResult.ok("src/main/java/X.java"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(applyPatchTool("src/main/java/X.java", HASH, patch),
                        applyPatchTool("src/main/java/X.java", HASH, patch),
                        finalResult(true, "patched after retry", "src/main/java/X.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(writer, times(2)).patchFile(workspaceId, "src/main/java/X.java", HASH, patch);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"ok\":false"));
    }

    @Test
    void applyPatchInfrastructureFailureMapsToInfrastructure() {
        String patch = "@@ -1,1 +1,1 @@\n-code\n+new code\n";
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, patch))
                .thenReturn(WorkspaceWriteResult.infraFail("src/main/java/X.java", "workspace root is not available"));
        when(llm.complete(anyString(), anyList())).thenReturn(applyPatchTool("src/main/java/X.java", HASH, patch));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("apply_patch infrastructure failure");
        verify(llm, times(1)).complete(anyString(), anyList());
    }

    @Test
    void writeFilePersistsToWorkspaceOnDisk(@TempDir Path baseDir) throws Exception {
        WorkspaceEntity workspace = workspaceWith("ws-1");
        LocalWorkspaceCodeWriter realWriter = realWriter(workspace, baseDir);
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn(writeTool("src/main/java/Y.java", "real content"),
                        finalResult(true, "implemented", "src/main/java/Y.java"));

        AgentRunOutcome outcome = new CodingAgent(llm, codeAccess, realWriter).run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        Path written = baseDir.resolve("ws-1").resolve("src/main/java/Y.java");
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("real content");
    }

    @Test
    void pathTraversalIsRejectedAndNeverPersists(@TempDir Path baseDir) {
        WorkspaceEntity workspace = workspaceWith("ws-1");
        LocalWorkspaceCodeWriter realWriter = realWriter(workspace, baseDir);
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn(writeTool("../escape.txt", "evil"), finalResult(true, "done", "../escape.txt"));

        AgentRunOutcome outcome = new CodingAgent(llm, codeAccess, realWriter).run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(Files.exists(baseDir.resolve("escape.txt"))).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"ok\":false"));
    }

    @Test
    void absolutePathOutsideWorkspaceIsRejected(@TempDir Path baseDir) {
        WorkspaceEntity workspace = workspaceWith("ws-1");
        LocalWorkspaceCodeWriter realWriter = realWriter(workspace, baseDir);
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        String absolute = Path.of(".").toAbsolutePath().resolve("evil.txt").toString();
        when(llm.complete(anyString(), anyList()))
                .thenReturn(writeTool(absolute, "evil"), finalResult(true, "done", absolute));

        AgentRunOutcome outcome = new CodingAgent(llm, codeAccess, realWriter).run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(Files.exists(Path.of(absolute))).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"ok\":false"));
    }

    @Test
    void toolExecutionFailureIsReportedAndFinalResultFails() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(codeAccess.readFile(workspaceId, "src/main/java/Missing.java"))
                .thenReturn(WorkspaceFileReadResult.fail("src/main/java/Missing.java", "file not found or unreadable"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(readTool("src/main/java/Missing.java"),
                        "{\"finalResult\":{\"success\":false,\"summary\":\"cannot proceed\",\"errors\":[\"missing file\"]}}");

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getCodingResult().isSuccess()).isFalse();
        assertThat(outcome.getCodingResult().getErrors()).contains("missing file");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"ok\":false"));
    }

    @Test
    void workspaceUnavailableWriteFailsInfrastructure(@TempDir Path baseDir) {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList())).thenReturn(writeTool("src/main/java/Y.java", "code"));

        AgentRunOutcome outcome = new CodingAgent(llm, codeAccess, unknownWriter(baseDir)).run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("write_file infrastructure failure");
        // 基础设施失败不进入模型纠正循环：只调一次 LLM，且失败不以 TOOL 结果回灌。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(1)).complete(anyString(), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).noneMatch(msg -> msg.role() == LlmMessage.Role.TOOL);
        assertThat(Files.exists(baseDir.resolve("ws-1").resolve("src/main/java/Y.java"))).isFalse();
    }

    @Test
    void infrastructureWriteFailureIsNotConfusedWithToolError() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "code"))
                .thenReturn(WorkspaceWriteResult.infraFail("src/main/java/Y.java", "workspace root is not available"));
        when(llm.complete(anyString(), anyList())).thenReturn(writeTool("src/main/java/Y.java", "code"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("write_file infrastructure failure");
        verify(llm, times(1)).complete(anyString(), anyList());
    }

    @Test
    void toolLevelWriteFailureIsFedBackForRetryAndNotInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "code"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/Y.java", "path escapes workspace root"),
                        WorkspaceWriteResult.ok("src/main/java/Y.java"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(writeTool("src/main/java/Y.java", "code"),
                        writeTool("src/main/java/Y.java", "code"),
                        finalResult(true, "fixed", "src/main/java/Y.java"));

        AgentRunOutcome outcome = agent().run(codingInput());

        // 工具级失败以 TOOL 结果回灌模型后能自我纠正，不判基础设施失败。
        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(writer, times(2)).writeFile(workspaceId, "src/main/java/Y.java", "code");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).complete(anyString(), historyCaptor.capture());
        // 第 2 轮历史包含第一次写入失败的 ok=false TOOL 结果，模型据此重试。
        assertThat(historyCaptor.getAllValues().get(1)).anySatisfy(msg ->
                assertThat(msg.content()).contains("\"ok\":false"));
    }

    @Test
    void llmCallFailureMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList())).thenThrow(new RuntimeException("llm boom"));

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("llm boom");
    }

    @Test
    void exceedingMaxToolRoundsFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList())).thenReturn(listTool());

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, times(MAX_TOOL_ROUNDS)).complete(anyString(), anyList());
    }

    @Test
    void finalResultPopulatesCodingResultFields() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn("""
                        {"finalResult":{"success":true,"summary":"added feature",
                          "modifiedFiles":["src/main/java/X.java","src/main/java/Y.java"],
                          "changes":["added class"],"errors":[]}}
                        """);

        AgentRunOutcome outcome = agent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        CodingResult coding = outcome.getCodingResult();
        assertThat(coding.isSuccess()).isTrue();
        assertThat(coding.getSummary()).isEqualTo("added feature");
        assertThat(coding.getModifiedFiles()).containsExactly("src/main/java/X.java", "src/main/java/Y.java");
        assertThat(coding.getChanges()).containsExactly("added class");
        assertThat(coding.getErrors()).isEmpty();
    }

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

    private WorkspaceEntity workspaceWith(String storageKey) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setStorageKey(storageKey);
        return workspace;
    }

    private WorkspaceMapper workspaceMapper(WorkspaceEntity workspace) {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        when(mapper.selectById(workspaceId)).thenReturn(workspace);
        return mapper;
    }

    private LocalWorkspaceCodeWriter realWriter(WorkspaceEntity workspace, Path baseDir) {
        return new LocalWorkspaceCodeWriter(new WorkspaceService(workspaceMapper(workspace), baseDir.toString()));
    }

    private LocalWorkspaceCodeWriter unknownWriter(Path baseDir) {
        WorkspaceMapper unknownMapper = mock(WorkspaceMapper.class);
        when(unknownMapper.selectById(workspaceId)).thenReturn(null);
        return new LocalWorkspaceCodeWriter(new WorkspaceService(unknownMapper, baseDir.toString()));
    }

    private String readTool(String path) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("toolCall").put("name", "read_file").putObject("arguments").put("path", path);
        return node.toString();
    }

    private String listTool() {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("toolCall").put("name", "list_files");
        return node.toString();
    }

    private String writeTool(String path, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode args = node.putObject("toolCall").put("name", "write_file").putObject("arguments");
        args.put("path", path);
        args.put("content", content);
        return node.toString();
    }

    private String applyPatchTool(String path, String hash, String patch) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode args = node.putObject("toolCall").put("name", "apply_patch").putObject("arguments");
        args.put("path", path);
        args.put("expectedHash", hash);
        args.put("patch", patch);
        return node.toString();
    }

    private String finalResult(boolean success, String summary, String modifiedFile) {
        ObjectNode finalNode = objectMapper.createObjectNode().put("success", success).put("summary", summary);
        if (modifiedFile != null) {
            finalNode.putArray("modifiedFiles").add(modifiedFile);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("finalResult", finalNode);
        return root.toString();
    }
}
