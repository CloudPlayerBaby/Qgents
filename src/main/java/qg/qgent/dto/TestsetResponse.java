package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Testset 的稳定公开响应，不暴露内部 JSON 存储结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestsetResponse {
    @Schema(description = "Testset ID")
    private UUID id;
    @Schema(description = "所属项目 ID")
    private UUID projectId;
    @Schema(description = "项目仓库绑定 ID")
    private UUID repositoryId;
    @Schema(description = "测试集名称")
    private String name;
    @Schema(description = "测试范围标签")
    private List<String> scopeTags;
    @Schema(description = "受控执行命令")
    private String command;
    @Schema(description = "超时秒数")
    private Integer timeoutSeconds;
    @Schema(description = "通过规则")
    private TestsetPassRule passRule;
    @Schema(description = "验收说明")
    private String acceptanceNotes;
    @Schema(description = "状态", allowableValues = {"ENABLED", "DISABLED"})
    private String status;
    @Schema(description = "创建用户 ID")
    private UUID createdBy;
    @Schema(description = "创建时间")
    private String createdAt;
    @Schema(description = "更新时间")
    private String updatedAt;
}
