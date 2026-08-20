package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 变更账本的工具结果汇总测试：验证「零变更失败」消息携带的尝试分布摘要（按工具聚合
 * 失败/无变化/成功，并展开最近失败原因）与上限语义，用于 requeue 上下文诊断。
 */
class ChangedWriteFactLedgerTest {

    private final ChangedWriteFactLedger ledger = new ChangedWriteFactLedger();

    @Test
    void summaryIsEmptyWithoutOutcomes() {
        assertThat(ledger.toolOutcomeSummary()).isEmpty();
    }

    @Test
    void summaryAggregatesFailuresAndNoopsPerTool() {
        ledger.recordToolOutcomes(List.of(
                new ToolOutcome("apply_patch", "src/A.java", false, false, "TOOL_PATCH_FORMAT_INVALID", true,
                        "hunk 声明行数与正文不一致"),
                new ToolOutcome("apply_patch", "src/A.java", false, false, "TOOL_PATCH_FORMAT_INVALID", true,
                        "补丁上下文与文件不一致"),
                new ToolOutcome("apply_patch", "src/A.java", true, false, null, false, null),
                new ToolOutcome("write_file", "src/B.java", true, true, null, false, null)));

        String summary = ledger.toolOutcomeSummary();

        assertThat(summary).contains("编码工具尝试汇总")
                .contains("apply_patch 共 3 次（失败 2 次、无变化 1 次）")
                .contains("write_file 共 1 次")
                .contains("最近失败：")
                .contains("apply_patch(src/A.java)")
                .contains("补丁上下文与文件不一致");
    }

    @Test
    void summaryCapsRecentFailuresAndKeepsMostRecentOutcomes() {
        for (int i = 0; i < ChangedWriteFactLedger.MAX_TOOL_OUTCOMES + 10; i++) {
            ledger.recordToolOutcomes(List.of(new ToolOutcome("apply_patch", "src/A.java", false, false,
                    "TOOL_PATCH_FORMAT_INVALID", true, "fail " + i)));
        }

        String summary = ledger.toolOutcomeSummary();

        // 超限丢弃最旧，仍保留最近结果供摘要；展开的最近失败不含被丢弃的最早一条。
        assertThat(summary).contains("失败 " + ChangedWriteFactLedger.MAX_TOOL_OUTCOMES + " 次");
        assertThat(summary).doesNotContain("fail 0");
    }

    @Test
    void inheritedPatchFailureCountSurvivesBeforeNextToolResult() {
        ChangedWriteFactLedger inherited = new ChangedWriteFactLedger(Map.of("src/A.java", 2));
        assertThat(inherited.patchFailureCounts()).containsEntry("src/A.java", 2);
        inherited.recordToolOutcomes(List.of(new ToolOutcome("apply_patch", "src/A.java", false, false,
                "TOOL_PATCH_REPAIR_REQUIRED", true, "repair required")));
        assertThat(inherited.patchFailureCounts()).containsEntry("src/A.java", 3);
    }
}
