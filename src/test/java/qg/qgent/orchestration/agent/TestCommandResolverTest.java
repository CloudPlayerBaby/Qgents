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
}
