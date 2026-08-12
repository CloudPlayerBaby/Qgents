package qg.qgent.orchestration;

import lombok.Data;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.UUID;

/**
 * Agent 的结构化输入：任务上下文 + 本相位步骤 + 循环反馈。
 * PLAN 阶段（方案 B）不创建 TaskRun，taskRunId/taskStepId 为 null。
 */
@Data
public class AgentInput {
    private UUID projectId;
    private UUID taskId;
    /** 本相位对应的 TaskRun，PLAN 阶段为 null。 */
    private UUID taskRunId;
    /** 本相位对应的 TaskStep，PLAN 阶段为 null。 */
    private UUID taskStepId;
    private OrchestrationPhase phase;
    private String taskTitle;
    private String requirement;
    /** 步骤指令或 PLAN 输入。 */
    private String instruction;
    /** 前一轮 Test/Review 失败项文本（仅质量修复循环时非空）。 */
    private String feedback;
    /** Workspace 只读摘要。 */
    private String workspaceSummary;
    /** 目标 Workspace，供 Agent 通过只读工具访问代码。 */
    private UUID workspaceId;
    /** Plan Agent 产出的结构化计划；仅 Coding 相位非空，供 CodingAgent 消费。 */
    private PlanResult planResult;
    /** Coding Agent 产出的结构化结果；仅 TESTING/REVIEWING 相位非空，供 Test/Review Agent 理解本次修改。 */
    private CodingResult codingResult;
    /** Test Agent 产出的结构化结果；仅 REVIEWING 相位或质量修复后的重试 Coding 非空。 */
    private TestResult testResult;
}
