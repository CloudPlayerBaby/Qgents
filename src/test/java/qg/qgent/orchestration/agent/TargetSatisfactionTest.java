package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标已满足判定的单元测试：仅依赖 {@link WorkspaceCodeAccess#listFiles} 返回的 Workspace
 * 相对路径做存在性核对，覆盖精确命中、目录前缀命中、缺失目标、空声明与核对失败五类输入。
 */
class TargetSatisfactionTest {

    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void satisfiedWhenAllTargetsExistExactly() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("repo-1/src/App.java", "repo-1/README.md"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("repo-1/src/App.java",
                "repo-1/README.md"))).isTrue();
    }

    @Test
    void satisfiedWhenTargetIsDirectoryPrefixOfExistingFile() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));

        // 声明目录（如 docs/）时，其下已有文件视为目标已满足。
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/main"))).isTrue();
    }

    @Test
    void notSatisfiedWhenAnyTargetMissing() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/main/java/X.java",
                "src/main/java/Y.java"))).isFalse();
    }

    @Test
    void notSatisfiedWhenTargetsNullOrEmpty() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));

        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, null)).isFalse();
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of())).isFalse();
    }

    @Test
    void notSatisfiedWhenListFilesEmptyOrThrows() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/App.java"))).isFalse();

        when(codeAccess.listFiles(any())).thenThrow(new IllegalStateException("workspace unavailable"));
        // 核对失败不误放行，回退到原零变更失败判定。
        assertThat(TargetSatisfaction.isSatisfied(codeAccess, workspaceId, List.of("src/App.java"))).isFalse();
    }
}
