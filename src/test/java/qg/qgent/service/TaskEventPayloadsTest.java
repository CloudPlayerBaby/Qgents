package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.TaskEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventPayloadsTest {

    @Test
    void taskUpdatedUsesControlledFailureReason() {
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(UUID.randomUUID());
        task.setFailureCode("FILE_PATCH_FAILED");
        task.setFailureReason("apply_patch 工具调用失败，模型原始文本");

        var payload = TaskEventPayloads.taskUpdated(task);

        assertThat(payload).containsEntry("failureCode", "FILE_PATCH_FAILED")
                .containsEntry("failureReason", "补丁无法应用，请重新读取文件后重试")
                .containsEntry("failureRetryable", true);
    }
}
