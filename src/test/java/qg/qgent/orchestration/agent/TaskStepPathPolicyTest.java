package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStepPathPolicyTest {

    @Test
    void exactFileIsAllowedButSiblingIsNot() {
        TaskStepPathPolicy policy = TaskStepPathPolicy.of(List.of("repo-2/what the fox said.txt"));

        assertThat(policy.allows("repo-2/what the fox said.txt")).isTrue();
        assertThat(policy.allows("repo-3/holy shit.txt")).isFalse();
    }

    @Test
    void directoryCreationCanReachParentOfDeclaredFile() {
        TaskStepPathPolicy policy = TaskStepPathPolicy.of(List.of("repo-2/src/main/App.java"));

        assertThat(policy.allowsDirectory("repo-2/src/main")).isTrue();
        assertThat(policy.allowsDirectory("repo-3/src/main")).isFalse();
    }

    @Test
    void rejectsAbsoluteAndParentTraversalPaths() {
        TaskStepPathPolicy policy = TaskStepPathPolicy.of(List.of("repo-2/file.txt"));

        assertThat(TaskStepPathPolicy.normalize("../repo-3/file.txt")).isNull();
        assertThat(TaskStepPathPolicy.normalize("C:/repo-3/file.txt")).isNull();
        assertThat(policy.allows("repo-2/../repo-3/file.txt")).isFalse();
    }

    @Test
    void emptyPolicyIsLegacyCompatible() {
        TaskStepPathPolicy policy = TaskStepPathPolicy.of(List.of());

        assertThat(policy.isLegacyUnrestricted()).isTrue();
        assertThat(policy.allows("legacy/file.txt")).isTrue();
    }
}
