package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目设置更新请求（PATCH，部分更新：未传字段不覆盖）。
 */
@Data
@NoArgsConstructor
public class ProjectSettingsUpdateRequest {

    /**
     * 允许成员创建需求群；关闭后仅 Project Admin 可建。
     */
    @Schema(description = "允许成员创建需求群")
    private Boolean allowCreateGroup;

    /**
     * 任务完成后自动归档群聊。
     */
    @Schema(description = "任务完成后自动归档群聊")
    private Boolean autoArchiveGroup;

    /**
     * 允许 @Agent 发起任务。
     */
    @Schema(description = "允许 @Agent 发起任务")
    private Boolean allowAgentTrigger;

    /**
     * 新成员自动加入所有需求群。
     */
    @Schema(description = "新成员自动加入所有需求群")
    private Boolean autoJoinAllGroups;
}
