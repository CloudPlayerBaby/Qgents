package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** 一条由项目管理员预先保存的 Testset 执行定义。 */
@Data
public class TestExecutionItemRequest {
    @NotNull private UUID testsetId;
    @NotBlank @Size(max = 4096) private String command;
    @NotNull @Min(1) @Max(3600) private Integer timeoutSeconds;
    @NotBlank @Pattern(regexp = "EXIT_CODE") private String passRuleType;
    @NotNull private Integer expectedExitCode;
}
