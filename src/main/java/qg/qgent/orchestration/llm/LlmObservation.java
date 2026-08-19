package qg.qgent.orchestration.llm;

import qg.qgent.orchestration.agent.ProtocolFailureCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;

/**
 * 单轮模型调用的结构化观测（脱敏）：用于把一次 Coding/Review 执行拆成可追溯的度量，
 * 随 TaskRun 产物摘要落库（阶段 A）。
 * <p>
 * 禁止记录：完整模型响应、完整 patch、源码、Token 用量、环境变量或宿主机路径。
 * 仅保存计划文档约定字段：phase / round / promptChars / responseChars / finishReason /
 * toolName / protocolFailureCode / responseSha256 以及服务端产生的 startedAt / finishedAt /
 * durationMs / status / errorCode。所有字段都经
 * {@link qg.qgent.service.TaskExecutionArtifactService#sanitizeSummary} 二次兜底。
 */
public record LlmObservation(
        String phase,
        int round,
        int promptChars,
        int responseChars,
        String finishReason,
        String toolName,
        ProtocolFailureCode protocolFailureCode,
        String responseSha256,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String status,
        String errorCode) {

    /** 兼容旧的观测构造调用；新调用应使用带时间的 of 工厂。 */
    public LlmObservation(String phase, int round, int promptChars, int responseChars, String finishReason,
                          String toolName, ProtocolFailureCode protocolFailureCode, String responseSha256) {
        this(phase, round, promptChars, responseChars, finishReason, toolName, protocolFailureCode, responseSha256,
                null, null, null, protocolFailureCode == null ? "PASSED" : "FAILED",
                protocolFailureCode == null ? null : protocolFailureCode.name());
    }

    /**
     * 由一轮原生工具循环的结果组装观测；phase/round 由 Agent 侧补充。
     */
    public static LlmObservation of(String phase, int round, ToolTurnResult turn) {
        return new LlmObservation(phase, round, turn.promptChars(), turn.responseChars(), turn.finishReason(),
                turn.toolName(), turn.protocolFailureCode(), turn.responseSha256());
    }

    /** 使用服务端观测时间构造一轮调用，时间不来自模型输出。 */
    public static LlmObservation of(String phase, int round, ToolTurnResult turn,
                                     Instant startedAt, Instant finishedAt) {
        long duration = startedAt == null || finishedAt == null
                ? 0L : Math.max(0L, java.time.Duration.between(startedAt, finishedAt).toMillis());
        String status = turn.protocolFailureCode() == null ? "PASSED" : "FAILED";
        return new LlmObservation(phase, round, turn.promptChars(), turn.responseChars(), turn.finishReason(),
                turn.toolName(), turn.protocolFailureCode(), turn.responseSha256(), startedAt, finishedAt,
                duration, status, turn.protocolFailureCode() == null ? null : turn.protocolFailureCode().name());
    }

    /**
     * 转为落库用摘要 Map，跳过 null 字段；键名与计划文档一致。
     */
    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("phase", phase);
        summary.put("round", round);
        summary.put("promptChars", promptChars);
        summary.put("responseChars", responseChars);
        if (finishReason != null) {
            summary.put("finishReason", finishReason);
        }
        if (toolName != null) {
            summary.put("toolName", toolName);
        }
        if (protocolFailureCode != null) {
            summary.put("protocolFailureCode", protocolFailureCode.name());
        }
        if (responseSha256 != null) {
            summary.put("responseSha256", responseSha256);
        }
        if (startedAt != null) {
            summary.put("startedAt", startedAt.toString());
        }
        if (finishedAt != null) {
            summary.put("finishedAt", finishedAt.toString());
        }
        if (durationMs != null) {
            summary.put("durationMs", durationMs);
        }
        if (status != null) {
            summary.put("status", status);
        }
        if (errorCode != null) {
            summary.put("errorCode", errorCode);
        }
        return summary;
    }
}
