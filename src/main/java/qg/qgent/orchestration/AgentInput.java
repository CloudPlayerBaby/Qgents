package qg.qgent.orchestration;

import lombok.Data;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextRepository;
import qg.qgent.dto.ContextSkill;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.List;
import java.util.UUID;

/**
 * Agent 的结构化输入：任务上下文 + 本 step 步骤 + 循环反馈 + 群聊/Skill/Memory 上下文。
 * PLAN bootstrap 也创建 PLANNER TaskRun（供失败诊断与重试审计）；正式执行图中的
 * CODING/TESTING/REVIEWING 输入均关联 TaskRun 与 TaskStep。
 * <p>
 * 群聊/Skill/Memory 上下文来自 {@code ContextService.buildForGroup}（后端4 已按用户+项目过滤），
 * 由 {@link AgentContextAssembler} 在每次 orchestrate 时快照一次注入；缺失时为空列表，属补充信息，
 * 不替代 taskTitle/requirement 核心需求。
 */
@Data
public class AgentInput {
    private UUID projectId;
    private UUID taskId;
    /**
     * 任务发起人用户 ID（通常为 Task.createdBy）；供运行时检索工具在服务端按此身份校验项目成员。
     */
    private UUID actorId;
    /**
     * 需求群 ID，供运行时聊天检索工具在当前群内补取历史信息。
     */
    private UUID requirementGroupId;
    /**
     * 本次正式执行对应的 TaskRun；PLAN bootstrap 时为 null。
     */
    private UUID taskRunId;
    /**
     * 本次执行关联的 TaskStep；PLAN bootstrap 仍关联 Planner Step。
     */
    private UUID taskStepId;
    private OrchestrationPhase phase;
    private String taskTitle;
    private String requirement;
    /**
     * 需求群标题（需求名称，来自 GroupContext.requirementTitle）；可能与 taskTitle 不同，保留群级称谓。
     */
    private String requirementTitle;
    /**
     * 需求群背景说明（来自 GroupContext.requirementDescription），比 task.requirement 更完整的讨论背景；
     * 可为 null/空，属补充信息，不替代 requirement 核心需求。
     */
    private String requirementDescription;
    /**
     * 步骤指令或 PLAN 输入。
     */
    private String instruction;
    /** 当前步骤的执行语义，优先于 Agent 角色决定写权限和成功条件。 */
    private String executionMode;
    /** 当前 TaskStep 的冻结可写路径；空值表示迁移前历史步骤兼容模式。 */
    private List<String> allowedPaths;
    /**
     * 当前 TaskStep 声明的目标文件（Workspace 相对路径）；供运行期判定目标是否已被前序步骤
     * 满足（目标已满足时无新增写入不算失败）。空值关闭该判定。
     */
    private List<String> targetFiles;
    /**
     * 前一轮重试反馈：质量循环携带 Test/Review finding；基础设施重试只携带稳定失败码与受控描述。
     */
    private String feedback;
    /** 结构化、限长、脱敏的重试失败上下文。 */
    private RetryContext retryContext;
    /**
     * Workspace 只读摘要。
     */
    private String workspaceSummary;
    /**
     * 目标 Workspace，供 Agent 通过只读工具访问代码。
     */
    private UUID workspaceId;
    /**
     * Plan Agent 产出的结构化计划；仅 Coding 相位非空，供 CodingAgent 消费。
     */
    private PlanResult planResult;
    /**
     * 当前 TEST 步骤冻结的按仓库验证命令（来自 TaskStep 持久化）；恢复续跑时
     * planResult 为 null，Test Agent 优先消费本字段的白名单命令，再回退自动探测。
     */
    private List<TaskStepEntity.VerificationCommand> verificationCommands;
    /**
     * Coding Agent 产出的结构化结果；仅 TESTING/REVIEWING 相位非空，供 Test/Review Agent 理解本次修改。
     */
    private CodingResult codingResult;
    /**
     * Test Agent 产出的结构化结果；仅 REVIEWING 相位或质量修复后的重试 Coding 非空。
     */
    private TestResult testResult;
    /**
     * 需求群近期消息（旧→新，默认 50 条），供 Agent 理解群聊讨论脉络；可为空列表。
     */
    private List<ContextMessage> conversation;
    /**
     * 项目已发布 Skill 目录（ID 与名称）。正文需由运行时 activate_skill 显式取得；可为空列表。
     */
    private List<ContextSkill> skills;
    /**
     * 项目已批准 Memory（架构约定/历史决策），供 Agent 复用确认知识；可为空列表。
     */
    private List<ContextMemory> memories;
    /** 当前 Task 实际可见的仓库清单及其 Workspace/分支映射。 */
    private List<ContextRepository> repositories;
}
