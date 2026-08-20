package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContentSanitizerTest {

    @Test
    void mapsPublicFailureDescriptionsWithoutReturningInternalText() {
        assertThat(ExecutionContentSanitizer.userFailureDescription("CODING_NO_ACTUAL_CHANGE"))
                .isEqualTo("代码步骤未产生实际文件变更");
        assertThat(ExecutionContentSanitizer.userFailureDescription("FILE_PATCH_FAILED"))
                .isEqualTo("补丁无法应用，请重新读取文件后重试");
        assertThat(ExecutionContentSanitizer.userFailureDescription("UNKNOWN_INTERNAL_CODE"))
                .isEqualTo("任务执行失败，请查看执行记录");
    }

    @Test
    void usesStableCodeForRetryability() {
        assertThat(ExecutionContentSanitizer.userFailureRetryable("FILE_HASH_MISMATCH")).isTrue();
        assertThat(ExecutionContentSanitizer.userFailureRetryable("DOCKER_EXEC_FAILED")).isTrue();
        assertThat(ExecutionContentSanitizer.userFailureRetryable("UNKNOWN_INTERNAL_CODE")).isFalse();
        assertThat(ExecutionContentSanitizer.userFailureRetryable(null)).isFalse();
    }

    @Test
    void keepsGitBranchNotFoundAsStablePublicCode() {
        assertThat(ExecutionContentSanitizer.stableInfrastructureCode("GIT_BRANCH_NOT_FOUND"))
                .isEqualTo("GIT_BRANCH_NOT_FOUND");
        assertThat(ExecutionContentSanitizer.publicFailureCode("GIT_BRANCH_NOT_FOUND"))
                .isEqualTo("GIT_BRANCH_NOT_FOUND");
        assertThat(ExecutionContentSanitizer.userFailureRetryable("GIT_BRANCH_NOT_FOUND")).isTrue();
        assertThat(ExecutionContentSanitizer.userFailureDescription("GIT_BRANCH_NOT_FOUND"))
                .contains("基线分支");
        // 未知内部码不应再被误映射为 GIT_BRANCH_NOT_FOUND
        assertThat(ExecutionContentSanitizer.publicFailureCode("UNKNOWN_INTERNAL_CODE")).isNull();
    }

    @Test
    void sanitizesDiagnosticOnlyValuesWithoutChangingPublicFailureMapping() {
        String detail = ExecutionContentSanitizer.sanitizeDiagnosticDetail(
                "TOKEN=secret-value endpoint=https://worker.internal/run path=C:\\worker\\secret "
                        + "command=./gradlew test\nProcess failed running ./gradlew test\n"
                        + "    at qg.qgent.Worker.execute(Worker.java:12)\nstderr=raw tool output");

        assertThat(detail).contains("[environment omitted]", "[endpoint omitted]", "[host path omitted]",
                "[command omitted]", "[stack frame omitted]", "[raw output omitted]");
        assertThat(detail).doesNotContain("secret-value", "worker.internal", "C:\\worker", "./gradlew", "raw tool output",
                "Worker.execute");
        assertThat(ExecutionContentSanitizer.infrastructureDescription("ANDROID_SDK_PATH_MISSING"))
                .isEqualTo("执行基础设施暂不可用");
    }

    @Test
    void keepsInternalGitFetchFailureCategoriesOutOfClientContracts() {
        assertThat(ExecutionContentSanitizer.stableInfrastructureCode("GIT_REMOTE_AUTH_FAILED"))
                .isEqualTo("GIT_STORE_FETCH_FAILED");
        assertThat(ExecutionContentSanitizer.stableInfrastructureCode("GIT_REMOTE_NETWORK_FAILED"))
                .isEqualTo("GIT_STORE_FETCH_FAILED");
        assertThat(ExecutionContentSanitizer.publicFailureCode("GIT_REMOTE_AUTH_FAILED"))
                .isEqualTo("GIT_STORE_FETCH_FAILED");
        assertThat(ExecutionContentSanitizer.infrastructureDescription("GIT_REMOTE_AUTH_FAILED"))
                .isEqualTo("代码仓库同步失败");
    }
}
