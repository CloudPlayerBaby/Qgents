package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.orchestration.tool.WorkspaceInfraException;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;
import qg.qgent.orchestration.tool.DevelopmentCommandId;
import qg.qgent.orchestration.tool.DevelopmentCommandPort;
import qg.qgent.orchestration.tool.DevelopmentCommandResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodingTools 类型化工具层测试：验证白名单工具的真实执行与失败语义——只读工具透传端口结果、
 * apply_patch 的 expectedHash 校验与基础设施失败中止、write_file 拒绝已存在文件（避免覆盖已有代码）、
 * 工具级失败返回 ok=false 而非抛异常。不写入任何 API Key。
 */
class CodingToolsTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String NEW_HASH = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String NEWER_HASH = "2222222222222222222222222222222222222222222222222222222222222222";

    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final UUID workspaceId = UUID.randomUUID();

    private CodingTools tools() {
        return new CodingTools(workspaceId, codeAccess, writer);
    }

    private CodingTools scopedTools(String... allowedPaths) {
        return new CodingTools(workspaceId, codeAccess, writer, List.of(allowedPaths));
    }

    @Test
    void developmentCommandUsesOnlyCommandIdAndDoesNotReturnRawOutput() {
        DevelopmentCommandPort commands = mock(DevelopmentCommandPort.class);
        when(commands.run(workspaceId, "repo-1", DevelopmentCommandId.MAVEN_TEST))
                .thenReturn(new DevelopmentCommandResult(false, "MAVEN_TEST", 1,
                        "PROCESS_EXIT_NONZERO", "固定开发命令执行失败"));
        CodingTools tools = new CodingTools(workspaceId, codeAccess, writer, List.of(), Map.of(), commands);

        Map<String, Object> result = tools.runDevelopmentCommand("MAVEN_TEST", "repo-1");

        assertThat(result).containsEntry("ok", false)
                .containsEntry("commandId", "MAVEN_TEST")
                .containsEntry("exitCode", 1)
                .doesNotContainKeys("command", "argv", "stdout", "stderr", "output");
        verify(commands).run(workspaceId, "repo-1", DevelopmentCommandId.MAVEN_TEST);
    }

    @Test
    void developmentCommandRejectsUnknownIdBeforeCallingPort() {
        DevelopmentCommandPort commands = mock(DevelopmentCommandPort.class);
        CodingTools tools = new CodingTools(workspaceId, codeAccess, writer, List.of(), Map.of(), commands);

        Map<String, Object> result = tools.runDevelopmentCommand("GIT_STATUS", null);

        assertThat(result).containsEntry("ok", false);
        verify(commands, never()).run(any(), any(), any());
    }

    @Test
    void listFilesReturnsOkWithPaths() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/X.java"));

        Map<String, Object> result = tools().listFiles();

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("files")).isEqualTo(List.of("src/main/java/X.java"));
    }

    @Test
    void readFileReturnsContentAndSha256() {
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));

        Map<String, Object> result = tools().readFile("src/main/java/X.java");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("path")).isEqualTo("src/main/java/X.java");
        assertThat(result.get("content")).isEqualTo("code");
        assertThat(result.get("sha256")).isEqualTo(HASH);
    }

    @Test
    void readFileMissingReturnsToolError() {
        when(codeAccess.readFile(workspaceId, "src/main/java/Missing.java"))
                .thenReturn(WorkspaceFileReadResult.fail("src/main/java/Missing.java", "file not found or unreadable"));

        Map<String, Object> result = tools().readFile("src/main/java/Missing.java");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("file not found or unreadable");
    }

    @Test
    void readFileBlankPathIsToolError() {
        Map<String, Object> result = tools().readFile("  ");

        assertThat(result.get("ok")).isEqualTo(false);
        verify(codeAccess, never()).readFile(any(), any());
    }

    @Test
    void searchCodeReturnsMatches() {
        when(codeAccess.searchCode(workspaceId, "class")).thenReturn(List.of("src/main/java/X.java"));

        Map<String, Object> result = tools().searchCode("class");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("matches")).isEqualTo(List.of("src/main/java/X.java"));
    }

    @Test
    void applyPatchAppliesThroughWriterWhenHashMatches() {
        String patch = "@@ -1 +1 @@\n-code\n+new code\n";
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, patch))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));

        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", HASH, patch);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("changed")).isEqualTo(true);
        assertThat(result.get("oldSha")).isEqualTo(HASH);
        assertThat(result.get("newSha")).isEqualTo("new-hash");
        // 结果最小化：绝不回塞 patch 或文件内容。
        assertThat(result).doesNotContainKey("patch").doesNotContainKey("content");
        verify(writer).patchFile(workspaceId, "src/main/java/X.java", HASH, patch);
    }

    @Test
    void applyPatchOmitsExpectedHashUsesKnownHashFromPriorRead() {
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        CodingTools tools = tools();
        tools.readFile("src/main/java/X.java");

        Map<String, Object> result = tools.applyPatch("src/main/java/X.java", null, "patch");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("oldSha")).isEqualTo(HASH);
        verify(writer).patchFile(workspaceId, "src/main/java/X.java", HASH, "patch");
    }

    @Test
    void applyPatchOmitsExpectedHashWithoutPriorReadIsToolError() {
        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", null, "patch");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("read_file first");
        verify(writer, never()).patchFile(any(), any(), any(), any());
    }

    @Test
    void applyPatchChainsNewHashFromSuccessfulWriteWithoutReread() {
        // 第一次补丁成功后，已知哈希更新为 NEW_HASH；第二次省略 expectedHash 直接命中。
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch1"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEW_HASH, true));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", NEW_HASH, "patch2"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEWER_HASH, true));
        CodingTools tools = tools();
        tools.applyPatch("src/main/java/X.java", HASH, "patch1");

        Map<String, Object> second = tools.applyPatch("src/main/java/X.java", null, "patch2");

        assertThat(second.get("ok")).isEqualTo(true);
        assertThat(second.get("oldSha")).isEqualTo(NEW_HASH);
        assertThat(second.get("newSha")).isEqualTo(NEWER_HASH);
        verify(writer).patchFile(workspaceId, "src/main/java/X.java", NEW_HASH, "patch2");
    }

    @Test
    void applyPatchInvalidHashIsToolErrorAndDoesNotWrite() {
        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", "not-a-hash", "patch");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("64-char hex");
        verify(writer, never()).patchFile(any(), any(), any(), any());
    }

    @Test
    void ensureTrailingNewlineOmitsExpectedHashUsesKnownHashFromPriorRead() {
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));
        when(writer.ensureTrailingNewline(workspaceId, "src/main/java/X.java", HASH))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "new-hash", true));
        CodingTools tools = tools();
        tools.readFile("src/main/java/X.java");

        Map<String, Object> result = tools.ensureTrailingNewline("src/main/java/X.java", null);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("changed")).isEqualTo(true);
        // 省略 expectedHash 时用本会话 read_file 确认过的哈希兜底，不再因参数缺失直接拒绝。
        verify(writer).ensureTrailingNewline(workspaceId, "src/main/java/X.java", HASH);
    }

    @Test
    void ensureTrailingNewlineOmitsExpectedHashWithoutPriorReadIsToolError() {
        Map<String, Object> result = tools().ensureTrailingNewline("src/main/java/X.java", null);

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("read_file first");
        verify(writer, never()).ensureTrailingNewline(any(), any(), any());
    }

    @Test
    void ensureTrailingNewlineChainsNewHashFromSuccessfulWriteWithoutReread() {
        // 第一次成功后账本更新为 NEW_HASH；第二次省略 expectedHash 直接命中新哈希。
        when(writer.ensureTrailingNewline(workspaceId, "src/main/java/X.java", HASH))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEW_HASH, true));
        when(writer.ensureTrailingNewline(workspaceId, "src/main/java/X.java", NEW_HASH))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEWER_HASH, false));
        CodingTools tools = tools();

        tools.ensureTrailingNewline("src/main/java/X.java", HASH);
        Map<String, Object> second = tools.ensureTrailingNewline("src/main/java/X.java", null);

        assertThat(second.get("ok")).isEqualTo(true);
        assertThat(second.get("changed")).isEqualTo(false);
        assertThat(second.get("newSha")).isEqualTo(NEWER_HASH);
        verify(writer).ensureTrailingNewline(workspaceId, "src/main/java/X.java", NEW_HASH);
    }

    @Test
    void applyPatchInfraFailureThrowsInfraException() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.infraFail("src/main/java/X.java", "workspace root is not available"));

        assertThatThrownBy(() -> tools().applyPatch("src/main/java/X.java", HASH, "patch"))
                .isInstanceOf(WorkspaceInfraException.class)
                .hasMessageContaining("apply_patch infrastructure failure");
    }

    @Test
    void applyPatchToolLevelFailureIsToolError() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "file has changed since read"));

        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", HASH, "patch");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("file has changed since read");
    }

    @Test
    void writeFileCreatesNewFileThroughWriter() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/Existing.java"));
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "new code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/Y.java", "new-hash", true));

        Map<String, Object> result = tools().writeFile("src/main/java/Y.java", "new code");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("changed")).isEqualTo(true);
        assertThat(result.get("oldSha")).isNull();
        assertThat(result.get("newSha")).isEqualTo("new-hash");
        assertThat(result).doesNotContainKey("content");
        verify(writer).writeFile(workspaceId, "src/main/java/Y.java", "new code");
    }

    @Test
    void scopedStepRejectsWriteToAnotherStepFile() {
        CodingTools tools = scopedTools("repo-2/what the fox said.txt");

        Map<String, Object> result = tools.writeFile("repo-3/holy shit.txt", "wrong step");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("outside the current TaskStep allowed paths");
        verify(writer, never()).writeFile(any(), any(), any());
    }

    @Test
    void scopedStepAllowsDeclaredFileAndRejectsTraversal() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "repo-2/what the fox said.txt", "ok"))
                .thenReturn(WorkspaceWriteResult.ok("repo-2/what the fox said.txt", NEW_HASH, true));
        CodingTools tools = scopedTools("repo-2/what the fox said.txt");

        Map<String, Object> allowed = tools.writeFile("repo-2/what the fox said.txt", "ok");
        Map<String, Object> traversal = tools.writeFile("repo-2/../repo-3/holy shit.txt", "wrong");

        assertThat(allowed.get("ok")).isEqualTo(true);
        assertThat(traversal.get("ok")).isEqualTo(false);
        verify(writer).writeFile(workspaceId, "repo-2/what the fox said.txt", "ok");
        verify(writer, never()).writeFile(workspaceId, "repo-3/holy shit.txt", "wrong");
    }

    @Test
    void legacyEmptyPolicyRemainsCompatible() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "legacy.txt", "ok"))
                .thenReturn(WorkspaceWriteResult.ok("legacy.txt", NEW_HASH, true));

        Map<String, Object> result = tools().writeFile("legacy.txt", "ok");

        assertThat(result.get("ok")).isEqualTo(true);
        verify(writer).writeFile(workspaceId, "legacy.txt", "ok");
    }

    @Test
    void writeFileRejectsExistingFileAsToolError() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/Y.java"));

        Map<String, Object> result = tools().writeFile("src/main/java/Y.java", "overwrite");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("only creates new files").contains("apply_patch");
        verify(writer, never()).writeFile(any(), any(), any());
    }

    @Test
    void writeFileInfraFailureThrowsInfraException() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "code"))
                .thenReturn(WorkspaceWriteResult.infraFail("src/main/java/Y.java", "workspace root is not available"));

        assertThatThrownBy(() -> tools().writeFile("src/main/java/Y.java", "code"))
                .isInstanceOf(WorkspaceInfraException.class)
                .hasMessageContaining("write_file infrastructure failure");
    }

    @Test
    void createDirectoryRecordsOnlyActualCreation() {
        when(writer.createDirectory(workspaceId, "src/main/java"))
                .thenReturn(WorkspaceDirectoryResult.ok("src/main/java", true));
        CodingTools tools = tools();

        Map<String, Object> result = tools.createDirectory("src/main/java");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("created")).isEqualTo(true);
        assertThat(tools.getModifiedDirectories()).containsExactly("src/main/java");
        assertThat(tools.getModifiedFiles()).isEmpty();
    }

    @Test
    void createDirectoryIdempotentResultDoesNotRecordChange() {
        when(writer.createDirectory(workspaceId, "src/main/java"))
                .thenReturn(WorkspaceDirectoryResult.ok("src/main/java", false));
        CodingTools tools = tools();

        Map<String, Object> result = tools.createDirectory("src/main/java");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("created")).isEqualTo(false);
        assertThat(tools.getModifiedDirectories()).isEmpty();
    }

    @Test
    void createDirectoryInfraFailureThrowsInfraException() {
        when(writer.createDirectory(workspaceId, "src/main/java"))
                .thenReturn(WorkspaceDirectoryResult.infraFail("src/main/java", "workspace unavailable"));

        assertThatThrownBy(() -> tools().createDirectory("src/main/java"))
                .isInstanceOf(WorkspaceInfraException.class)
                .hasMessageContaining("create_directory infrastructure failure");
    }

    @Test
    void writeObserverFiresOnActualDirectoryCreation() {
        when(writer.createDirectory(workspaceId, "src/main/java"))
                .thenReturn(WorkspaceDirectoryResult.ok("src/main/java", true));

        toolsWithObserver().createDirectory("src/main/java");

        verify(observer).onWrite(eq(PROJECT_ID), eq(TASK_ID), eq(TASK_RUN_ID), eq(workspaceId),
                any(WorkspaceDirectoryResult.class));
    }

    @Test
    void writeObserverDoesNotFireForExistingDirectory() {
        when(writer.createDirectory(workspaceId, "src/main/java"))
                .thenReturn(WorkspaceDirectoryResult.ok("src/main/java", false));

        toolsWithObserver().createDirectory("src/main/java");

        verify(observer, never()).onWrite(any(), any(), any(), any(), any());
    }

    // ---- 阶段 D：成功写后的 Preview 回调（CodingWriteObserver） ----

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID TASK_RUN_ID = UUID.randomUUID();

    private final CodingWriteObserver observer = mock(CodingWriteObserver.class);

    private CodingTools toolsWithObserver() {
        CodingTools tools = tools();
        tools.setWriteObserver(observer, PROJECT_ID, TASK_ID, TASK_RUN_ID);
        return tools;
    }

    @Test
    void writeObserverFiresOnWriteFileSuccess() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "new code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/Y.java", NEW_HASH, true));

        toolsWithObserver().writeFile("src/main/java/Y.java", "new code");

        verify(observer).onWrite(eq(PROJECT_ID), eq(TASK_ID), eq(TASK_RUN_ID), eq(workspaceId),
                any(WorkspaceWriteResult.class));
    }

    @Test
    void writeObserverFiresOnApplyPatchSuccess() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEW_HASH, true));

        toolsWithObserver().applyPatch("src/main/java/X.java", HASH, "patch");

        verify(observer).onWrite(eq(PROJECT_ID), eq(TASK_ID), eq(TASK_RUN_ID), eq(workspaceId),
                any(WorkspaceWriteResult.class));
    }

    @Test
    void writeObserverNotFiredOnToolLevelFailure() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "file has changed since read"));

        Map<String, Object> result = toolsWithObserver().applyPatch("src/main/java/X.java", HASH, "patch");

        assertThat(result.get("ok")).isEqualTo(false);
        verify(observer, never()).onWrite(any(), any(), any(), any(), any());
    }

    @Test
    void patchFormatFailureReturnsStructuredRecoveryGuidance() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "bad patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java",
                        "FILE_PATCH_FAILED: hunk 声明行数与正文不一致"));

        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", HASH, "bad patch");

        assertThat(result).containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_PATCH_FORMAT_INVALID")
                .containsEntry("retryable", true)
                .containsEntry("nextAction", "不要重复原 patch；先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 unified diff；新文件改用 write_file");
    }

    @Test
    void workerPatchFailureCodeTakesPrecedenceWhenReasonDoesNotContainCode() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "bad patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));

        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", HASH, "bad patch");

        assertThat(result).containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_PATCH_FORMAT_INVALID")
                .containsEntry("retryable", true)
                .containsEntry("nextAction", "不要重复原 patch；先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 unified diff；新文件改用 write_file");
    }

    @Test
    void workerHashFailureCodeTakesPrecedenceWhenReasonDoesNotContainCode() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_HASH_MISMATCH",
                        "文件已经发生变化，请重新读取后再写入"));

        Map<String, Object> result = tools().applyPatch("src/main/java/X.java", HASH, "patch");

        assertThat(result).containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_CONFLICT")
                .containsEntry("retryable", true)
                .containsEntry("nextAction", "先重新 read_file 获取当前 sha256，再用 apply_patch");
    }

    @Test
    void writeObserverNotFiredWhenNotConfigured() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/Y.java", NEW_HASH, true));

        tools().writeFile("src/main/java/Y.java", "code");

        verify(observer, never()).onWrite(any(), any(), any(), any(), any());
    }

    @Test
    void writeObserverExceptionIsSwallowedAndWriteStillSucceeds() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEW_HASH, true));
        doThrow(new IllegalStateException("preview store down"))
                .when(observer).onWrite(any(), any(), any(), any(), any());

        Map<String, Object> result = toolsWithObserver().applyPatch("src/main/java/X.java", HASH, "patch");

        // 预览回调失败只记日志，绝不破坏 Coding 主循环。
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("newSha")).isEqualTo(NEW_HASH);
        verify(observer).onWrite(eq(PROJECT_ID), eq(TASK_ID), eq(TASK_RUN_ID), eq(workspaceId),
                any(WorkspaceWriteResult.class));
    }

    @Test
    void modifiedFilesTracksSuccessfulWritesOnly() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/Existing.java"));
        when(codeAccess.readFile(workspaceId, "src/main/java/ReadOnly.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/ReadOnly.java", "code", HASH));
        when(writer.writeFile(workspaceId, "src/main/java/New.java", "code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/New.java", NEW_HASH, true));
        when(writer.patchFile(workspaceId, "src/main/java/Existing.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/Existing.java", NEWER_HASH, true));
        CodingTools tools = tools();
        tools.readFile("src/main/java/ReadOnly.java");
        tools.writeFile("src/main/java/New.java", "code");
        tools.applyPatch("src/main/java/Existing.java", HASH, "patch");

        // 只有成功写入的文件被记录；read_file（只读）不记。
        assertThat(tools.getModifiedFiles())
                .containsExactlyInAnyOrder("src/main/java/New.java", "src/main/java/Existing.java");
    }

    // ---------- apply_patch 连续失败升级：切换为带 Hash 校验的 replace_file ----------

    @Test
    void applyPatchConsecutiveFailuresRequireReplaceFile() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        when(writer.replaceFile(workspaceId, "src/main/java/X.java", HASH, "full content"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEW_HASH, true));
        CodingTools tools = tools();

        for (int i = 0; i < CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD; i++) {
            assertThat(tools.applyPatch("src/main/java/X.java", HASH, "patch").get("ok")).isEqualTo(false);
        }

        Map<String, Object> overwrite = tools.replaceFile("src/main/java/X.java", HASH, "full content");

        assertThat(overwrite.get("ok")).isEqualTo(true);
        assertThat(overwrite.get("changed")).isEqualTo(true);
        verify(writer).replaceFile(workspaceId, "src/main/java/X.java", HASH, "full content");
    }

    @Test
    void applyPatchFailuresBelowThresholdKeepWriteFileRejected() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        CodingTools tools = tools();

        for (int i = 0; i < CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD - 1; i++) {
            tools.applyPatch("src/main/java/X.java", HASH, "patch");
        }

        Map<String, Object> result = tools.writeFile("src/main/java/X.java", "overwrite");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("only creates new files");
        verify(writer, never()).writeFile(any(), any(), any());
    }

    @Test
    void applyPatchSuccessResetsConsecutiveFailureCounter() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/X.java"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "bad"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "good"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", NEW_HASH, true));
        CodingTools tools = tools();

        tools.applyPatch("src/main/java/X.java", HASH, "bad");
        tools.applyPatch("src/main/java/X.java", HASH, "bad");
        tools.applyPatch("src/main/java/X.java", HASH, "good"); // 成功，重置连续失败计数
        tools.applyPatch("src/main/java/X.java", HASH, "bad");
        tools.applyPatch("src/main/java/X.java", HASH, "bad"); // 成功后又失败 2 次 < 3

        Map<String, Object> result = tools.writeFile("src/main/java/X.java", "overwrite");

        assertThat(result.get("ok")).isEqualTo(false);
        verify(writer, never()).writeFile(any(), any(), any());
    }

    @Test
    void applyPatchEscalationMessageDirectsFullFileRewrite() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        CodingTools tools = tools();

        Map<String, Object> result = null;
        for (int i = 0; i < CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD; i++) {
            result = tools.applyPatch("src/main/java/X.java", HASH, "patch");
        }

        assertThat(result).containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_PATCH_REPAIR_REQUIRED")
                .containsEntry("retryable", true);
        assertThat((String) result.get("error")).contains("replace_file");
        assertThat((String) result.get("nextAction")).contains("replace_file").contains("完整文件内容");
    }

    @Test
    void replaceFileHashConflictDoesNotWrite() {
        when(writer.replaceFile(workspaceId, "src/main/java/X.java", HASH, "full content"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_HASH_MISMATCH",
                        "file has changed since read"));

        Map<String, Object> result = tools().replaceFile("src/main/java/X.java", HASH, "full content");

        assertThat(result).containsEntry("ok", false)
                .containsEntry("errorCode", "TOOL_CONFLICT")
                .containsEntry("retryable", true);
        verify(writer).replaceFile(workspaceId, "src/main/java/X.java", HASH, "full content");
    }

    @Test
    void failedReplaceAfterEscalationKeepsUnrecoverableMarker() {
        when(writer.patchFile(workspaceId, "src/main/java/X.java", HASH, "patch"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED", "bad patch"));
        when(writer.replaceFile(workspaceId, "src/main/java/X.java", HASH, "full content"))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_HASH_MISMATCH",
                        "file changed since read"));
        CodingTools tools = tools();

        for (int i = 0; i < CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD; i++) {
            tools.applyPatch("src/main/java/X.java", HASH, "patch");
        }
        Map<String, Object> result = tools.replaceFile("src/main/java/X.java", HASH, "full content");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(tools.getLastToolError()).contains("TOOL_PATCH_REPAIR_REQUIRED");
    }

    @Test
    void drainOutcomesCapturesEveryWriteToolResult() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/New.java", "code"))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/New.java", NEW_HASH, true));
        CodingTools tools = tools();

        tools.writeFile("src/main/java/New.java", "code");
        List<ToolOutcome> drained = tools.drainOutcomes();
        List<ToolOutcome> again = tools.drainOutcomes();

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).toolName()).isEqualTo("write_file");
        assertThat(drained.get(0).ok()).isTrue();
        assertThat(drained.get(0).changed()).isTrue();
        assertThat(again).isEmpty();
    }
}
