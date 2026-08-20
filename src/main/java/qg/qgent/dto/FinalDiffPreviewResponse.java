package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 最终 Diff 的群聊卡片预览。
 *
 * <p>这是轻量展示数据，不替代完整 Diff 详情或审核接口。每次仅返回所选文件前 200 条解析行；
 * 当文件或行被截断时，客户端使用 detailPath 跳转完整 Diff 页面。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinalDiffPreviewResponse {
    @Schema(description = "最终 Diff ID")
    private String diffId;
    @Schema(description = "完整 Diff 前端详情页路径，固定以 /app/projects 开头")
    private String detailPath;
    @Schema(description = "每个文件最多返回的预览行数，固定为 200")
    private Integer previewLineLimit;
    @Schema(description = "Diff 文件总数")
    private Long totalFileCount;
    @Schema(description = "文件标签是否仅返回前 100 项")
    private Boolean filesTruncated;
    @Schema(description = "可切换文件标签，按 Diff 内 sequence 升序")
    private List<DiffPreviewFileResponse> files;
    @Schema(description = "当前选中的文件 ID；无文件时为 null")
    private String selectedFileId;
    @Schema(description = "当前文件可解析行数；truncated=false 时为准确值，truncated=true 时固定为 201，表示至少 201 行")
    private Integer totalLineCount;
    @Schema(description = "当前文件实际返回的行，最多 200 条")
    private List<DiffPreviewLineResponse> lines;
    @Schema(description = "当前文件行数是否超过 previewLineLimit")
    private Boolean truncated;
    @Schema(description = "文件或行超出卡片容量时为 true，前端显示查看详情入口")
    private Boolean viewDetailsRequired;
}
