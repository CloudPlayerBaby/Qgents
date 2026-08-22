package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.tool.WorkspaceChangeResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 当前 TaskRun 内由写工具返回的可信变更事实账本。
 * <p>
 * 账本只接收成功且 {@code changed=true} 的相对路径，并限制条目数，避免工具历史压缩后
 * 丢失早期写入事实，也避免把无界事实重新注入模型上下文。另收集每次写工具调用的脱敏结果
 * （{@link ToolOutcome}），供最终「零变更失败」门禁汇总本次 run 的失败/无变化/成功分布。
 */
final class ChangedWriteFactLedger {

    static final int MAX_CHANGED_PATHS = 64;
    /** 工具结果事实的上限；超出时丢弃最旧条目，保证摘要只反映最近一次 run 内的尝试。 */
    static final int MAX_TOOL_OUTCOMES = 64;
    /** 摘要里最多展开的最近失败条目数，避免失败消息过长。 */
    private static final int SUMMARY_RECENT_FAILURES = 3;
    /** 单条失败说明在摘要中的最大长度。 */
    private static final int SUMMARY_ERROR_MAX_CHARS = 120;

    private final Set<String> changedFiles = new LinkedHashSet<>();
    private final Set<String> changedDirectories = new LinkedHashSet<>();
    private final List<ToolOutcome> toolOutcomes = new ArrayList<>();
    private final Map<String, Integer> patchFailureCounts = new LinkedHashMap<>();
    private String lastToolError;

    ChangedWriteFactLedger() {
    }

    ChangedWriteFactLedger(Map<String, Integer> previousCounts) {
        if (previousCounts != null) {
            previousCounts.forEach((path, count) -> {
                if (path != null && !path.isBlank() && count != null && count > 0) {
                    patchFailureCounts.put(path, Math.min(count, CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD));
                }
            });
        }
    }

    void recordPatchFailureCounts(Map<String, Integer> counts) {
        if (counts != null) {
            counts.forEach((path, count) -> {
                if (path != null && !path.isBlank() && count != null && count > 0) {
                    patchFailureCounts.put(path, Math.min(count, CodingTools.PATCH_FAILURE_ESCALATION_THRESHOLD));
                }
            });
        }
    }

    void recordToolFailure(String error) {
        if (error != null && !error.isBlank()) {
            lastToolError = error;
        }
    }

    String lastToolError() {
        return lastToolError;
    }

    String recoveryHint() {
        if (lastToolError == null) {
            return "";
        }
        if (lastToolError.contains("TOOL_PATCH_REPAIR_REQUIRED")) {
            return "；建议：停止生成 patch，先 read_file 获取最新内容和 sha256，再调用 replace_file；replace_file 仍失败后结束本次运行";
        }
        if (lastToolError.contains("FILE_PATCH_FAILED") || lastToolError.contains("PATCH_")) {
            return "；建议：先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 patch";
        }
        return "；建议：根据工具 errorCode 和 nextAction 修正参数后再试";
    }

    /**
     * 返回尚未被同一路径成功写入消解的、可通过后续工具继续处理的最近失败。
     *
     * <p>模型在工具错误后直接给出 {@code success=false} 时，不能只依赖模型自行复述错误；
     * 必须把服务端记录的失败事实与下一步动作带入纠正回合。这里只覆盖已有明确替代路径的
     * 文件存在、版本冲突、补丁格式和参数错误，不把路径越界、权限或基础设施错误误判为可继续。</p>
     */
    String correctiveToolGuidance() {
        Set<String> resolvedPaths = new LinkedHashSet<>();
        for (int index = toolOutcomes.size() - 1; index >= 0; index--) {
            ToolOutcome outcome = toolOutcomes.get(index);
            if (outcome == null) {
                continue;
            }
            if (outcome.ok() && outcome.path() != null) {
                resolvedPaths.add(outcome.path());
                continue;
            }
            if (outcome.ok() || !isCorrectable(outcome)
                    || (outcome.path() != null && resolvedPaths.contains(outcome.path()))) {
                continue;
            }
            return "服务端工具记录：" + outcome.toolName() + "(" + outcome.path() + ") 失败："
                    + shortError(outcome) + "。下一步必须：" + nextAction(outcome);
        }
        return "";
    }

    boolean hasCorrectableToolFailure() {
        return !correctiveToolGuidance().isBlank();
    }

    private static boolean isCorrectable(ToolOutcome outcome) {
        if ("TOOL_CONFLICT".equals(outcome.errorCode())
                || "TOOL_PATCH_FORMAT_INVALID".equals(outcome.errorCode())
                || "TOOL_ARGUMENT_INVALID".equals(outcome.errorCode())) {
            return true;
        }
        String error = outcome.error();
        return "write_file".equals(outcome.toolName()) && error != null
                && (error.contains("only creates new files") || error.contains("already exists"));
    }

    private static String nextAction(ToolOutcome outcome) {
        String error = outcome.error() == null ? "" : outcome.error();
        if ("write_file".equals(outcome.toolName())
                && (error.contains("only creates new files") || error.contains("already exists"))) {
            return "先 read_file 获取现有内容和 sha256，再调用 apply_patch 修改该文件；不要再次调用 write_file";
        }
        if ("TOOL_CONFLICT".equals(outcome.errorCode())) {
            return "先重新 read_file 获取当前 sha256，再调用 apply_patch";
        }
        if ("TOOL_PATCH_FORMAT_INVALID".equals(outcome.errorCode())) {
            return "先重新 read_file 获取最新内容和 sha256，再按实际内容重新生成 unified diff";
        }
        return "依据工具 schema 修正参数后重新调用相应工具，不要直接结束";
    }

    void recordToolOutcomes(List<ToolOutcome> outcomes) {
        if (outcomes == null) {
            return;
        }
        for (ToolOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (toolOutcomes.size() >= MAX_TOOL_OUTCOMES) {
                toolOutcomes.remove(0);
            }
            toolOutcomes.add(outcome);
            if ("apply_patch".equals(outcome.toolName()) && outcome.path() != null) {
                if (outcome.ok() && outcome.changed()) {
                    patchFailureCounts.remove(outcome.path());
                } else if (!outcome.ok() && ("TOOL_PATCH_FORMAT_INVALID".equals(outcome.errorCode())
                        || "TOOL_PATCH_REPAIR_REQUIRED".equals(outcome.errorCode()))) {
                    patchFailureCounts.merge(outcome.path(), 1, Integer::sum);
                }
            }
        }
    }

    Map<String, Integer> patchFailureCounts() {
        return Map.copyOf(patchFailureCounts);
    }

    /**
     * 汇总本次 run 内写工具的尝试分布（按工具名聚合成功/失败/无变化），并展开最近失败
     * 的具体原因；无任何工具结果时返回空串。只含脱敏字段，不含 patch、文件内容或绝对路径。
     */
    String toolOutcomeSummary() {
        if (toolOutcomes.isEmpty()) {
            return "";
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        List<String> recentFailures = new ArrayList<>();
        for (ToolOutcome outcome : toolOutcomes) {
            int[] bucket = counts.computeIfAbsent(outcome.toolName(), key -> new int[3]);
            if (!outcome.ok()) {
                bucket[1]++;
            } else if (!outcome.changed()) {
                bucket[2]++;
            } else {
                bucket[0]++;
            }
            if (!outcome.ok() && recentFailures.size() < SUMMARY_RECENT_FAILURES) {
                recentFailures.add(outcome.toolName() + "(" + outcome.path() + "): " + shortError(outcome));
            }
        }
        StringBuilder summary = new StringBuilder("编码工具尝试汇总：");
        boolean first = true;
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            if (!first) {
                summary.append("；");
            }
            first = false;
            int[] bucket = entry.getValue();
            int total = bucket[0] + bucket[1] + bucket[2];
            summary.append(entry.getKey()).append(" 共 ").append(total).append(" 次");
            if (bucket[1] > 0 || bucket[2] > 0) {
                summary.append("（");
                boolean needSeparator = false;
                if (bucket[1] > 0) {
                    summary.append("失败 ").append(bucket[1]).append(" 次");
                    needSeparator = true;
                }
                if (bucket[2] > 0) {
                    if (needSeparator) {
                        summary.append("、");
                    }
                    summary.append("无变化 ").append(bucket[2]).append(" 次");
                }
                summary.append("）");
            }
        }
        if (!recentFailures.isEmpty()) {
            summary.append("；最近失败：").append(String.join("；", recentFailures));
        }
        return summary.toString();
    }

    private static String shortError(ToolOutcome outcome) {
        String error = outcome.error();
        if (error == null || error.isBlank()) {
            return outcome.errorCode() == null ? "未知错误" : outcome.errorCode();
        }
        return error.length() <= SUMMARY_ERROR_MAX_CHARS ? error : error.substring(0, SUMMARY_ERROR_MAX_CHARS - 3) + "...";
    }

    CodingWriteObserver observing(CodingWriteObserver delegate) {
        return (projectId, taskId, taskRunId, workspaceId, result) -> {
            record(result);
            if (delegate != null) {
                delegate.onWrite(projectId, taskId, taskRunId, workspaceId, result);
            }
        };
    }

    void record(WorkspaceChangeResult result) {
        if (result == null || !result.isOk() || !result.isChanged()
                || result.getPath() == null || result.getPath().isBlank()) {
            return;
        }
        Set<String> paths = result instanceof qg.qgent.orchestration.tool.WorkspaceDirectoryResult
                ? changedDirectories : changedFiles;
        if (paths.size() < MAX_CHANGED_PATHS || paths.contains(result.getPath())) {
            paths.add(result.getPath());
        }
    }

    boolean hasChangedWrite() {
        return !changedFiles.isEmpty() || !changedDirectories.isEmpty();
    }

    List<String> changedPaths() {
        return List.copyOf(changedFiles);
    }

    List<String> changedDirectories() {
        return List.copyOf(changedDirectories);
    }

    void addTo(List<String> target) {
        if (target == null) {
            return;
        }
        List<String> missing = new ArrayList<>(changedFiles);
        missing.removeAll(target);
        target.addAll(missing);
    }
}
