package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 创建项目 Testset 的请求。
 */
@Data
public class TestsetCreateRequest {
    @NotBlank
    @Size(max = 255)
    @Schema(description = "测试集名称")
    private String name;

    @NotNull
    @Schema(description = "项目仓库绑定 ID")
    private UUID repositoryId;

    @NotNull
    @Size(max = 32)
    @Schema(description = "测试范围标签")
    private List<@NotBlank @Size(max = 64) String> scopeTags;

    @NotBlank
    @Size(max = 4096)
    @Schema(description = "在受控 Sandbox 中执行的命令，仅支持 mvn test、gradle test、npm test、npm run lint、./mvnw test 或 ./gradlew test")
    private String command;

    @NotNull
    @Min(1)
    @Max(3600)
    @Schema(description = "超时秒数", example = "900")
    private Integer timeoutSeconds;

    @NotNull
    @Valid
    @Schema(description = "通过规则")
    private TestsetPassRule passRule;

    @Size(max = 4000)
    @Schema(description = "验收说明")
    private String acceptanceNotes;
}
