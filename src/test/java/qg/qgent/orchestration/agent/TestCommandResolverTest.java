package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCommandResolverTest {

    private final TestCommandResolver resolver = new TestCommandResolver();

    @Test
    void usesWorkspaceRelativeMavenWrapper() {
        assertThat(resolver.resolve(List.of("pom.xml", "mvnw")))
                .containsExactly("./mvnw", "test");
    }

    @Test
    void usesWorkspaceRelativeGradleWrapper() {
        assertThat(resolver.resolve(List.of("build.gradle", "gradlew")))
                .containsExactly("./gradlew", "test");
    }

    @Test
    void fallsBackToInstalledBuildToolWhenWrapperIsAbsent() {
        assertThat(resolver.resolve(List.of("pom.xml")))
                .containsExactly("mvn", "test");
        assertThat(resolver.resolve(List.of("build.gradle")))
                .containsExactly("gradle", "test");
    }

    @Test
    void treatsWindowsOnlyWrappersAsAbsentInLinuxSandbox() {
        assertThat(resolver.resolveCommand(List.of("backend/pom.xml", "backend/mvnw.cmd")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("mvn", "test"), "backend"));
        assertThat(resolver.resolveCommand(List.of("frontend/build.gradle", "frontend/gradlew.cmd")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("gradle", "test"), "frontend"));
    }

    @Test
    void bindsMultiRepositoryGradleWrapperToTheMatchingRepository() {
        TestCommandResolver.ResolvedCommand command = resolver.resolveCommand(List.of(
                "frontend/package.json", "backend/build.gradle.kts", "backend/gradlew"));

        assertThat(command.repositoryPath()).isEqualTo("backend");
        assertThat(command.command()).containsExactly("sh", "./gradlew", "test");
    }

    @Test
    void bindsNestedRepositoryPathWithoutTruncatingAtFirstSegment() {
        TestCommandResolver.ResolvedCommand command = resolver.resolveCommand(List.of(
                "services/backend/pom.xml", "services/backend/mvnw", "services/backend/src/Main.java",
                "web/package.json"));

        assertThat(command.repositoryPath()).isEqualTo("services/backend");
        assertThat(command.command()).containsExactly("sh", "./mvnw", "test");
    }
}
