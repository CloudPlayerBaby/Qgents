package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Memory 来源消息引用（契约 §9）。
 */
@Data
public class MemorySourceRef {

    /** 消息所属需求群 ID。 */
    @NotNull
    @Schema(description = "消息所属需求群 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID groupId;

    /** 消息 ID。 */
    @NotNull
    @Schema(description = "消息 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID messageId;
}
