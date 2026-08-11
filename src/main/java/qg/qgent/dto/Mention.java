package qg.qgent.dto;

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
    private String type;

    /** 被提及的用户或 Agent ID。 */
    @NotNull
    private UUID id;
}
