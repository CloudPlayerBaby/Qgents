package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TestFailureClassifier 纯单元测试：验证超时/依赖/网络/服务失败的环境分类，以及"输出引用已修改文件
 * 或改动了构建文件则强制视为代码缺陷"的守卫，确保环境关键字不误放走真实缺陷。
 */
class TestFailureClassifierTest {

    private final TestFailureClassifier classifier = new TestFailureClassifier();

    private TestFailureClassifier.Verdict classify(int exitCode, String out, String err, List<String> targets) {
        return classifier.classify(exitCode, out, err, targets);
    }

    @Test
    void timeoutExitCodeIsEnvironment() {
        TestFailureClassifier.Verdict verdict = classify(124, "partial output", "", List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("TEST_EXECUTION_TIMEOUT");
    }

    @Test
    void timeoutTextIsEnvironment() {
        TestFailureClassifier.Verdict verdict = classify(1, "", "reached the timeout of 10 minutes",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("TEST_EXECUTION_TIMEOUT");
    }

    @Test
    void dependencyResolutionFailureIsEnvironment() {
        TestFailureClassifier.Verdict verdict = classify(1, "",
                "[ERROR] Could not resolve dependencies for project com.example:app:1.0", List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("TEST_DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void modifiedBuildFileKeepsDependencyFailureAsCodeDefect() {
        // 改动了 pom.xml 仍出现依赖解析失败：更可能是改动引入（坐标/版本错误），不得归环境。
        TestFailureClassifier.Verdict verdict = classify(1, "",
                "[ERROR] Could not resolve dependencies", List.of("pom.xml", "src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.CODE_DEFECT);
        assertThat(verdict.failureCode()).isNull();
    }

    @Test
    void networkUnreachableIsEnvironment() {
        TestFailureClassifier.Verdict verdict = classify(1, "", "UnknownHost: maven.central", List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("TEST_NETWORK_UNAVAILABLE");
    }

    @Test
    void serviceConnectionRefusedIsEnvironment() {
        TestFailureClassifier.Verdict verdict = classify(1, "", "Connection refused to host mysql:3306",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("TEST_SERVICE_UNAVAILABLE");
    }

    @Test
    void outputReferencingModifiedFileMasksEnvironmentKeyword() {
        // 输出引用已修改文件 X.java：失败可能与改动相关，即使命中 Connection refused 也按代码缺陷处理。
        TestFailureClassifier.Verdict verdict = classify(1, "", "X.java:12 Connection refused",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.CODE_DEFECT);
        assertThat(verdict.failureCode()).isNull();
    }

    @Test
    void ordinaryTestFailureIsCodeDefect() {
        TestFailureClassifier.Verdict verdict = classify(1, "2 tests failed, 1 error", "", List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.CODE_DEFECT);
        assertThat(verdict.failureCode()).isNull();
    }

    @Test
    void emptyOutputWithNonZeroExitIsCodeDefect() {
        TestFailureClassifier.Verdict verdict = classify(2, "", "", List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.CODE_DEFECT);
        assertThat(verdict.failureCode()).isNull();
    }

    @Test
    void nullTargetsNeverTriggerEnvironmentKeywords() {
        TestFailureClassifier.Verdict verdict = classify(1, "", "Connection refused", null);

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("TEST_SERVICE_UNAVAILABLE");
    }

    @Test
    void sdkLocationNotFoundIsEnvironment() {
        // Android SDK 缺失：明确的环境缺陷，修代码无法凭空补环境，不得回 Coding。
        TestFailureClassifier.Verdict verdict = classify(1, "",
                "ERROR: SDK location not found. Define location with an ANDROID_SDK_ROOT environment variable",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("BUILD_ENVIRONMENT_UNAVAILABLE");
    }

    @Test
    void unableToLocateJavaRuntimeIsEnvironment() {
        // Gradle/JDK 缺失：构建工具链问题，归环境。
        TestFailureClassifier.Verdict verdict = classify(1, "",
                "Unable to locate a Java Runtime to invoke javac. Please check that Java is installed",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("BUILD_ENVIRONMENT_UNAVAILABLE");
    }

    @Test
    void mavenNoCompilerProvidedIsEnvironment() {
        // Maven 运行在 JRE 而非 JDK 上：无 javac 可用，环境问题。
        TestFailureClassifier.Verdict verdict = classify(1, "",
                "No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.ENVIRONMENT);
        assertThat(verdict.failureCode()).isEqualTo("BUILD_ENVIRONMENT_UNAVAILABLE");
    }

    @Test
    void modifiedBuildFileKeepsToolchainFailureAsCodeDefect() {
        // 改动了 pom.xml 时即使出现 SDK 缺失输出也按代码缺陷处理：改动构建文件后环境类关键词
        // 不可信（可能是配置引入），守卫优先级高于工具链识别。
        TestFailureClassifier.Verdict verdict = classify(1, "",
                "SDK location not found", List.of("pom.xml", "src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.CODE_DEFECT);
        assertThat(verdict.failureCode()).isNull();
    }

    @Test
    void outputReferencingModifiedFileMasksToolchainKeyword() {
        // 输出引用已修改文件 X.java：即使命中 SDK 缺失也按代码缺陷处理。
        TestFailureClassifier.Verdict verdict = classify(1, "", "X.java:12 SDK location not found",
                List.of("src/X.java"));

        assertThat(verdict.classification()).isEqualTo(TestFailureClassifier.Classification.CODE_DEFECT);
        assertThat(verdict.failureCode()).isNull();
    }
}
