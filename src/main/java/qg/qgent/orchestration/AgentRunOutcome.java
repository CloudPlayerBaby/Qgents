package qg.qgent.orchestration;

import lombok.Data;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 执行的结构化输出，按相位携带对应的结果对象。
 * outcome 由状态机消费以决定下一步；结果对象供 Orchestrator 路由为下一 Agent 的输入。
 */
@Data
public class AgentRunOutcome {
    private OrchestrationPhase phase;
    private RunOutcome outcome;
    private PlanResult planResult;
    private CodingResult codingResult;
    private TestResult testResult;
    private ReviewResult reviewResult;
    /**
     * 结果摘要或失败原因（已脱敏）。
     */
    private String message;
    /**
     * 稳定失败分类码（如 AGENT_RUN_TIMEOUT / ORPHANED_RUN_TIMEOUT），用于随 Run 产物摘要落库定位；
     * 仅失败且可分类时非空。
     */
    private String failureCode;
    /**
     * 仅用于内部失败诊断表的原始分类码；不会进入 TaskRun、执行产物、SSE 或公开接口。
     */
    private String diagnosticFailureCode;
    /**
     * 仅用于内部失败诊断表的来源标签；用于区分 Agent、超时与编排启动等失败边界。
     */
    private String diagnosticSource;
    /**
     * 仅用于内部失败诊断表的异常类型；不保存堆栈。
     */
    private String diagnosticExceptionType;
    /**
     * 仅用于内部失败诊断表的待脱敏详情；调用方不得写入命令、原始输出或凭据。
     */
    private String diagnosticDetail;
    /**
     * 本相位每次模型调用的脱敏观测（阶段 A）：随 Run 产物摘要落库，失败时可定位错误码。
     * 缺失时为 null（PLAN/LEGACY 或未执行模型调用），落库侧做空值兼容。
     */
    private List<LlmObservation> observations;
    /**
     * 本次运行实际通过 activate_skill 读取的 Skill ID。仅用于紧随其后的质量回修重新校验并注入；
     * 不携带 Skill 正文，不能作为跨 Project 或跨 Task 的授权凭据。
     */
    private List<UUID> activatedSkillIds = new ArrayList<>();
    /** Coding 工具按相对路径累计的连续 patch 失败次数，供下一次 TaskRun 继承。 */
    private Map<String, Integer> patchFailureCounts = new LinkedHashMap<>();
    /**
     * Coding 自报失败时是否仍存在真实写入证据（changed=true 的文件/目录写入）。
     * 供编排证据门控区分"模型误判/中途放弃"与"确实未产生任何变更"两种自报失败：
     * 前者可同相位有界重试，后者维持立即终态，避免 no-op 重试回环。
     */
    private boolean hasRealChanges;

    /**
     * 追加一条观测；首次调用时惰性初始化列表。
     */
    public void addObservation(LlmObservation observation) {
        if (observation == null) {
            return;
        }
        if (observations == null) {
            observations = new ArrayList<>();
        }
        observations.add(observation);
    }
}
