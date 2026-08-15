package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Testset 的通过条件。当前仅支持按进程退出码判断。
 */
@Data
public class TestsetPassRule {
    /**
     * 规则类型，当前固定为 EXIT_CODE。
     */
    @NotBlank
    @Pattern(regexp = "EXIT_CODE")
    @Schema(description = "通过规则类型", allowableValues = "EXIT_CODE")
    private String type;

    /**
     * 期望的进程退出码。
     */
    @NotNull
    @Schema(description = "期望退出码", example = "0")
    private Integer expected;
}
