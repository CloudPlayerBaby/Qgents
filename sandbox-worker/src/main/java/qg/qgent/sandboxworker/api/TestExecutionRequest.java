package qg.qgent.sandboxworker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 受控测试执行请求；workspaceId 与 ref 必须二选一。
 */
@Data
public class TestExecutionRequest {
    @NotNull
    private UUID executionId;
    @NotNull
    private UUID projectId;
    @NotNull
    private UUID repositoryId;
    private UUID workspaceId;
    @Size(max = 512)
    private String ref;
    @Size(max = 512)
    private String mergeSourceRef;
    @NotEmpty
    @Size(max = 32)
    private List<@Valid TestExecutionItemRequest> testsets;
}
