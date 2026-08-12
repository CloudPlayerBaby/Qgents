package qg.qgent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 根据选中的群聊消息生成 AI 草稿请求（契约 §9）。
 */
@Data
public class MemoryDraftRequest {

    /** 作为知识依据的来源消息列表（至少 1 条）。 */
    @Valid
    @NotEmpty
    private List<MemorySourceRef> sourceMessages;

    /** 沉淀指令，如「沉淀为项目认证安全约定」。 */
    @NotBlank
    @Size(max = 2048)
    private String instruction;
}
