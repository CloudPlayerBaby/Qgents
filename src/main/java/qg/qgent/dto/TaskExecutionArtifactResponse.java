package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 任务执行产物展示项（不可变时间线产物）。
 * <p>
 * 在保留原始结构化 summary 的同时增加结构化展示字段：title（由产物类型派生的展示标题）、
 * status（由执行结果派生的状态）、description（脱敏说明）、resources（受权限保护的内部资源引用列表）。
 * 当前无校验过的内部资源引用时 resources 返回空列表，不伪造。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionArtifactResponse {

    /** 产物 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "产物 ID")
    private String id;

    /** 所属任务 ID。 */
    @Schema(description = "所属任务 ID")
    private String taskId;

    /** 产生产物的任务运行 ID；PLAN 产物为 null。 */
    @Schema(description = "所属任务运行 ID")
    private String taskRunId;

    /** 产生产物的任务步骤 ID；PLAN 产物为 null。 */
    @Schema(description = "所属任务步骤 ID")
    private String taskStepId;

    /** 任务内时间线序号。 */
    @Schema(description = "时间线序号")
    private Integer sequenceNo;

    /** 产物类型：PLAN/CODING/TESTING/REVIEWING。 */
    @Schema(description = "产物类型")
    private String artifactType;

    /** 展示标题（由产物类型派生）。 */
    @Schema(description = "展示标题")
    private String title;

    /** 产物状态（由执行结果派生），如 SUCCEEDED/FAILED。 */
    @Schema(description = "产物状态")
    private String status;

    /** 脱敏展示说明，可为 null。 */
    @Schema(description = "脱敏展示说明")
    private String description;

    /** 原始结构化摘要 JSON。 */
    @Schema(description = "原始结构化摘要")
    private Map<String, Object> summary;

    /** 受权限保护的内部资源引用列表；无则空数组。 */
    @Schema(description = "内部资源引用")
    private List<ArtifactResource> resources;

    /** 创建时间（UTC）。 */
    @Schema(description = "创建时间")
    private String createdAt;
}
