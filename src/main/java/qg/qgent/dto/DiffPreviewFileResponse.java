package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群聊 Diff 卡可切换文件的展示摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffPreviewFileResponse {
    @Schema(description = "Diff 文件 ID")
    private String fileId;
    @Schema(description = "Diff 内文件顺序")
    private Long sequence;
    @Schema(description = "仓库相对路径")
    private String path;
    @Schema(description = "仅文件名，包含后缀")
    private String fileName;
    @Schema(description = "文件后缀，不含点；无后缀时为 null")
    private String extension;
    @Schema(description = "变更类型：ADDED/MODIFIED/DELETED/RENAMED")
    private String changeType;
    @Schema(description = "新增行数")
    private Integer additions;
    @Schema(description = "删除行数")
    private Integer deletions;
    @Schema(description = "是否二进制文件")
    private Boolean binary;
}
