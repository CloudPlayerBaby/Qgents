package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群聊 Diff 卡单行预览。
 *
 * <p>content 不携带 unified diff 的 +/- 前缀；前端必须根据 type 渲染删除、增加和上下文颜色。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffPreviewLineResponse {
    @Schema(description = "行类型：CONTEXT/DELETE/ADD")
    private String type;
    @Schema(description = "旧文件行号；新增行时为 null")
    private Integer oldLineNo;
    @Schema(description = "新文件行号；删除行时为 null")
    private Integer newLineNo;
    @Schema(description = "不含 +/- 前缀的代码行内容")
    private String content;
    @Schema(description = "代码行内容是否超过 4000 个 Unicode 字符并被截断")
    private Boolean contentTruncated;
}
