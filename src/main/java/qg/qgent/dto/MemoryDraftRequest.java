package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 自动沉淀 Memory 草稿请求（契约 §9）。
 * <p>
 * 客户端不再主动勾选来源消息：只需指定「当前打开的群」，后端自动检索该群最近聊天，
 * 由 AI 生成一份草稿投给用户/Admin 确认；来源消息由后端自动记录。
 */
@Data
public class MemoryDraftRequest {

    /**
     * 取最近聊天的需求群 ID（前端当前打开的群）。
     */
    @NotNull
    @Schema(description = "取最近聊天的需求群 ID（前端当前打开的群）", requiredMode = Schema.RequiredMode.REQUIRED)
    private java.util.UUID groupId;

    /**
     * 沉淀指令（可空，如「沉淀为项目认证安全约定」）；为空时 AI 使用缺省指令。
     */
    @Size(max = 2048)
    @Schema(description = "沉淀指令（可空），如「沉淀为项目认证安全约定」", maxLength = 2048)
    private String instruction;
}
