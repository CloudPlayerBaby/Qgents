package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标已满足判定的单元测试：依据 {@link WorkspaceCodeAccess#readFile} 的结果——目标必须存在
 * 且内容非空才算已满足（空文件/目录/读取失败均不算），避免文件存在但内容错误被误判完成。
 */
class TargetSatisfactionTest {

    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void satisfiedWhenAllTargetsExistAndNonEmpty() {
        when(codeAccess.readFile(any(), any())).thenReturn(
                WorkspaceFileReadResult.ok("repo-1/src/App.java", "class App {}", "abc",
                        true, "LF"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("repo-1/src/App.java",
                "repo-1/README.md"))).isTrue();
    }

    @Test
    void notSatisfiedWhenTargetIsDirectory() {
        // 目录不是文件：readFile 对目录路径失败 → 不视为已满足（空目录/目录声明不兜底）。
        when(codeAccess.readFile(any(), any())).thenReturn(WorkspaceFileReadResult.fail("src/main",
                "not a regular file"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/main"))).isFalse();
    }

    @Test
    void notSatisfiedWhenAnyTargetMissing() {
        when(codeAccess.readFile(any(), any())).thenReturn(WorkspaceFileReadResult.fail("src/main/java/Y.java",
                "file not found"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/main/java/X.java",
                "src/main/java/Y.java"))).isFalse();
    }

    @Test
    void notSatisfiedWhenTargetsNullOrEmpty() {
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, null)).isFalse();
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of())).isFalse();
    }

    @Test
    void notSatisfiedWhenTargetFileIsEmpty() {
        // 空文件不算「目标已满足」——空文件可能是本次任务该写入内容却只创建了占位。
        when(codeAccess.readFile(any(), any())).thenReturn(WorkspaceFileReadResult.ok("src/App.java", "", "abc",
                false, "NONE"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/App.java"))).isFalse();
    }

    @Test
    void notSatisfiedWhenReadFailsOrThrows() {
        when(codeAccess.readFile(any(), any())).thenReturn(WorkspaceFileReadResult.fail("src/App.java", "unreadable"));
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/App.java"))).isFalse();

        when(codeAccess.readFile(any(), any())).thenThrow(new IllegalStateException("workspace unavailable"));
        // 核对失败不误放行，回退到原零变更失败判定。
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/App.java"))).isFalse();
    }
}
