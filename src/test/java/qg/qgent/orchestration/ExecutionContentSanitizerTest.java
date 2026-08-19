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
}
