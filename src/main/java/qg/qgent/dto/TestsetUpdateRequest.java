package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** 局部修改 Testset 的请求；未提供的字段保持不变。 */
@Data
public class TestsetUpdateRequest {
    @Size(max = 255)
    @Schema(description = "测试集名称")
    private String name;

    @Schema(description = "项目仓库绑定 ID")
    private UUID repositoryId;

    @Size(max = 32)
    @Schema(description = "测试范围标签")
    private List<@jakarta.validation.constraints.NotBlank @Size(max = 64) String> scopeTags;

    @Size(max = 4096)
    @Schema(description = "在受控 Sandbox 中执行的命令")
    private String command;

    @Min(1)
    @Max(3600)
    @Schema(description = "超时秒数")
    private Integer timeoutSeconds;

    @Valid
    @Schema(description = "通过规则")
    private TestsetPassRule passRule;

    @Size(max = 4000)
    @Schema(description = "验收说明")
    private String acceptanceNotes;
}
