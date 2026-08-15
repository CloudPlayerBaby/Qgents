package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目设置：需求群规则开关（成员B 后端接口补充清单 §二）。
 * <p>
 * GET /projects/{projectId}/settings 返回完整设置（默认值兜底）；
 * PATCH 支持部分更新（空字段不覆盖）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSettings {

    /**
     * 允许成员创建需求群；关闭后仅 Project Admin 可建。
     */
    @Schema(description = "允许成员创建需求群；关闭后仅 Project Admin 可建", defaultValue = "true")
    private boolean allowCreateGroup = true;

    /**
     * 任务完成后自动归档群聊。
     */
    @Schema(description = "任务完成后自动归档群聊", defaultValue = "false")
    private boolean autoArchiveGroup = false;

    /**
     * 允许 @Agent 发起任务；关闭后群内不显示「发起任务」按钮。
     */
    @Schema(description = "允许 @Agent 发起任务", defaultValue = "true")
    private boolean allowAgentTrigger = true;

    /**
     * 新成员自动加入所有需求群。
     */
    @Schema(description = "新成员自动加入所有需求群", defaultValue = "false")
    private boolean autoJoinAllGroups = false;
}
