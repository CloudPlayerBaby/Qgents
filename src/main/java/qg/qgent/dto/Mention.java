package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 消息提及对象（契约 §7 mentions）。
 */
@Data
public class Mention {

    /** 提及对象类型枚举：USER / AGENT。 */
    @NotBlank
    @Size(max = 16)
    @Schema(description = "提及对象类型：USER / AGENT", maxLength = 16, requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    /** 被提及的用户或 Agent ID。 */
    @NotNull
    @Schema(description = "被提及的用户或 Agent ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;
}
