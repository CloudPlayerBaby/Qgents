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
}
