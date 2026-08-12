package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 调用沙箱工具时提交的结构化请求。
 */
@Data
public class ToolExecutionRequest {
    @NotNull
    private UUID executionId;

    private UUID repositoryId;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[a-z]+\\.[a-z]+")
    private String tool;

    @NotNull
    @Size(max = 64)
    private Map<String, Object> arguments = new LinkedHashMap<>();

    @Min(1)
    @Max(3600)
    private Long timeoutSeconds;
}
