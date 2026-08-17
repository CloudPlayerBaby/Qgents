package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * 邀请项目成员入群请求（契约 2026-08-17 群成员选择与管理）。
 */
@Data
public class GroupMemberAddRequest {

    /**
     * 被邀请的项目成员 userId（必填）。
     */
    @NotNull
    @Schema(description = "被邀请的项目成员 userId（必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID userId;
}
