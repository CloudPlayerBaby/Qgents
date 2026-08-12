package qg.qgent.dto;

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
    private UUID groupId;

    /** 消息 ID。 */
    @NotNull
    private UUID messageId;
}
