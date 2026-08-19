package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 群聊上下文（点3：聊天上下文管理）。
 * <p>
 * 将需求群的历史消息、需求、关联仓库、已发布 Skill 目录与已批准 Memory 组装为 Agent 输入上下文，
 * 供 Agent 编排系统（后端1）在创建 Task / 运行 Agent 时作为 prompt 输入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupContext {

    /**
     * 需求群 ID。
     */
    @Schema(description = "需求群 ID")
    private String groupId;

    /**
     * 所属项目 ID。
     */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /**
     * 需求群标题（需求名称）。
     */
    @Schema(description = "需求群标题")
    private String requirementTitle;

    /**
     * 需求背景说明。
     */
    @Schema(description = "需求背景说明")
    private String requirementDescription;

    /**
     * 需求群关联的项目仓库绑定 ID 列表。
     */
    @Schema(description = "需求群关联的项目仓库绑定 ID 列表")
    private List<String> repositoryIds;

    /**
     * 需求群允许使用的仓库清单；任务运行时会补充 Workspace 别名和实际分支。
     */
    @Schema(description = "需求群关联仓库的可读清单")
    private List<ContextRepository> repositories;

    /**
     * 近期群聊消息（新→旧或旧→新，供 Agent 理解对话历史）。
     */
    @Schema(description = "近期群聊消息")
    private List<ContextMessage> conversation;

    /**
     * 项目可见的已发布 Skill 目录（仅 ID 与名称，正文需显式激活）。
     */
    @Schema(description = "项目已发布 Skill 目录，仅包含 ID 与名称")
    private List<ContextSkill> skills;

    /**
     * 项目已批准的 Memory（供 Agent 复用确认知识）。
     */
    @Schema(description = "项目已批准的 Memory")
    private List<ContextMemory> memories;

    /** 兼容旧快照和测试构造：旧数据没有仓库 manifest 时视为空列表。 */
    public GroupContext(String groupId, String projectId, String requirementTitle, String requirementDescription,
                        List<String> repositoryIds, List<ContextMessage> conversation,
                        List<ContextSkill> skills, List<ContextMemory> memories) {
        this(groupId, projectId, requirementTitle, requirementDescription, repositoryIds, List.of(),
                conversation, skills, memories);
    }
}
