package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Testset 配置视图（契约 §10）。
 * <p>
 * repositoryId 为项目仓库绑定 ID，为空表示项目通用；enabled 由 status 派生（ENABLED→true），
 * 供前端直接控制开关展示。definition 为测试定义 JSON（命令/超时/通过条件等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestsetResponse {

    /** Testset ID（UUIDv7，字符串形式）。 */
    @Schema(description = "Testset ID")
    private String id;

    /** 测试集名称。 */
    @Schema(description = "测试集名称")
    private String name;

    /** 限定的项目仓库绑定 ID；为空表示项目通用。 */
    @Schema(description = "限定的项目仓库绑定 ID")
    private String repositoryId;

    /** 状态：ENABLED/DISABLED。 */
    @Schema(description = "状态：ENABLED/DISABLED")
    private String status;

    /** 是否启用（status==ENABLED）。 */
    @Schema(description = "是否启用")
    private boolean enabled;

    /** 测试定义 JSON，包含命令、超时和通过条件。 */
    @Schema(description = "测试定义 JSON")
    private Map<String, Object> definition;

    /** 创建用户 ID。 */
    @Schema(description = "创建用户 ID")
    private String createdBy;

    /** 创建时间（UTC）。 */
    @Schema(description = "创建时间")
    private String createdAt;

    /** 更新时间（UTC）。 */
    @Schema(description = "更新时间")
    private String updatedAt;
}
