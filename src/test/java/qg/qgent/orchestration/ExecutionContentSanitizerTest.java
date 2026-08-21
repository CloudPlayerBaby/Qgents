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
    void keepsQualityStageStableCodesAsPublicWithoutRetry() {
        // 项目未配置测试命令：确定性配置问题，稳定码公开、不可重试（质量循环不退回 Coding）。
        assertThat(ExecutionContentSanitizer.publicFailureCode("TEST_COMMAND_NOT_FOUND"))
                .isEqualTo("TEST_COMMAND_NOT_FOUND");
        assertThat(ExecutionContentSanitizer.userFailureDescription("TEST_COMMAND_NOT_FOUND"))
                .contains("未检测到受支持的项目/测试命令");
        assertThat(ExecutionContentSanitizer.userFailureRetryable("TEST_COMMAND_NOT_FOUND")).isFalse();

        // 审查验收目标缺失：稳定码公开；可回到 Coding 补齐，因此用户可重试。
        assertThat(ExecutionContentSanitizer.publicFailureCode("REVIEW_ASSERTION_TARGET_NOT_FOUND"))
                .isEqualTo("REVIEW_ASSERTION_TARGET_NOT_FOUND");
        assertThat(ExecutionContentSanitizer.userFailureDescription("REVIEW_ASSERTION_TARGET_NOT_FOUND"))
                .contains("验收目标");
        assertThat(ExecutionContentSanitizer.userFailureRetryable("REVIEW_ASSERTION_TARGET_NOT_FOUND")).isTrue();
    }

    @Test
    void exposesAccountAccessDeniedAsSafeUserMessage() {
        assertThat(ExecutionContentSanitizer.stableInfrastructureCode("LLM_ACCOUNT_ACCESS_DENIED"))
                .isEqualTo("LLM_ACCOUNT_ACCESS_DENIED");
        assertThat(ExecutionContentSanitizer.infrastructureDescription("LLM_ACCOUNT_ACCESS_DENIED"))
                .contains("模型服务账号");
        assertThat(ExecutionContentSanitizer.userFailureDescription("LLM_ACCOUNT_ACCESS_DENIED"))
                .contains("API 权限")
                .doesNotContain("Access denied");
        assertThat(ExecutionContentSanitizer.publicFailureCode("LLM_ACCOUNT_ACCESS_DENIED"))
                .isEqualTo("LLM_ACCOUNT_ACCESS_DENIED");
        assertThat(ExecutionContentSanitizer.userFailureRetryable("LLM_ACCOUNT_ACCESS_DENIED")).isFalse();
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

    @Test
    void supersededDiffReviewIsPublicNonRetryableAndHasNoRecovery() {
        // 被后续 Workspace 修改取代：稳定码公开、不可重试（重试无法重新交付旧 Diff 快照）。
        assertThat(ExecutionContentSanitizer.publicFailureCode("DIFF_REVIEW_SUPERSEDED"))
                .isEqualTo("DIFF_REVIEW_SUPERSEDED");
        assertThat(ExecutionContentSanitizer.userFailureDescription("DIFF_REVIEW_SUPERSEDED"))
                .isEqualTo("Diff 已被后续修改取代");
        assertThat(ExecutionContentSanitizer.userFailureRetryable("DIFF_REVIEW_SUPERSEDED")).isFalse();
    }
}
