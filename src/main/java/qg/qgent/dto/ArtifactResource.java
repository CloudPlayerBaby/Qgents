package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行产物资源引用摘要（TaskArtifact 结构化展示用）。
 * <p>
 * resourceType 枚举：DIFF/TEST_REPORT/API_CONTRACT/BUILD_PREVIEW/SDK_PACKAGE/ACCEPTANCE_REPORT/TASK_RUN；
 * resourceId 指向受权限保护的内部资源，不返回未经校验的任意外部 URL。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactResource {

    /**
     * 资源类型。
     */
    @Schema(description = "资源类型")
    private String resourceType;

    /**
     * 内部资源 ID（受权限保护）。
     */
    @Schema(description = "内部资源 ID")
    private String resourceId;

    /**
     * 展示标题，如“查看测试报告”。
     */
    @Schema(description = "展示标题")
    private String title;
}
