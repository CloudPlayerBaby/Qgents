package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 修改需求群请求（契约 §7，PATCH 语义：null 字段表示不修改）。
 */
@Data
public class GroupUpdateRequest {

    /**
     * 新群标题（≤255，null 表示不修改）。
     */
    @Size(max = 255)
    @Schema(description = "新群标题，null 表示不修改", maxLength = 255)
    private String title;

    /**
     * 新群说明（≤4096，null 表示不修改）。
     */
    @Size(max = 4096)
    @Schema(description = "新群说明，null 表示不修改", maxLength = 4096)
    private String description;

    /**
     * 新的关联仓库 ID 列表（null 表示不修改；非 null 则整体替换）。
     */
    @Schema(description = "新的关联仓库 ID 列表，null 表示不修改，非 null 整体替换")
    private List<UUID> repositoryIds;
}
