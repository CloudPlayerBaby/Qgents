package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.result.ReviewResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性 Review 通过判定：以 LLM 输出的 finding 严重度为唯一权威（镜像 Test 侧 exitCode 权威），
 * LLM 的 success/needsCodingFix 布尔值不参与判定。宽松策略：不引入任何机器关键词去覆盖 LLM 的
 * 严重度判断（避免误伤与打回死循环），只做两件事——
 * <ol>
 *   <li>findings 为空 → 直接 PASS（与 {@link TestFailureClassifier} 的 exitCode==0 永不判环境
 *       对称：零问题即无物可修，打回 Coding 必空转）；</li>
 *   <li>风格降级（宽松方向）：MAJOR 且文本命中纯风格白名单（命名/注释/格式/可读性/未使用 import
 *       等）时降为 MINOR，让鸡毛蒜皮不拦路；BLOCKER 永不降级。</li>
 * </ol>
 * 归一化后存在 BLOCKER/MAJOR → FAIL，否则 PASS。输出归一化后的 findings 供 ReviewAgent 回写，
 * 使持久化严重度与最终判定一致。
 * <p>
 * 设计取舍：风格降级不做"正确性关键词刹车"——MAJOR 文本同时含风格词与缺陷描述时也会被降级放过。
 * 这是刻意的宽松：缺陷在 Test 已兜底、产出 Diff 用户可见可纠正的前提下，容忍漏过换取流程不卡死。
 */
public class ReviewVerdictComputer {

    /** 判定结果：是否通过 + 归一化后的 findings（严重度已按规则清洗）。 */
    public record Verdict(boolean passed, List<ReviewResult.Finding> normalizedFindings) {
    }

    /** 纯风格白名单：MAJOR 命中即降为 MINOR；BLOCKER 不受影响。 */
    private static final String[] STYLE_PATTERNS = {
            "命名", "naming", "变量名", "类名", "方法名",
            "注释", "comment",
            "格式", "format", "缩进", "indent",
            "可读性", "readability",
            "未使用", "unused", "无用", "dead code",
            "import", "导包",
            "风格", "style",
            "拼写", "typo", "错别字",
            "过长", "超长"
    };

    /**
     * 依据归一化后的严重度计算是否通过审查。
     *
     * @param findings Review 解析出的原始发现列表（可为 null 或空）。
     * @return 判定结果；normalizedFindings 始终非 null，缺失输入时为空列表。
     */
    public Verdict compute(List<ReviewResult.Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return new Verdict(true, new ArrayList<>());
        }
        List<ReviewResult.Finding> normalized = new ArrayList<>(findings.size());
        boolean hasBlockerOrMajor = false;
        for (ReviewResult.Finding finding : findings) {
            ReviewResult.Finding effective = normalize(finding);
            if (hasBlockerOrMajor(effective)) {
                hasBlockerOrMajor = true;
            }
            normalized.add(effective);
        }
        return new Verdict(!hasBlockerOrMajor, normalized);
    }

    /** 宽松方向唯一变换：MAJOR 且文本命中纯风格词 → 降为 MINOR；其余（含 BLOCKER）保持原级。 */
    private ReviewResult.Finding normalize(ReviewResult.Finding finding) {
        String severity = severityOf(finding);
        if ("MAJOR".equals(severity) && matchesAny(textOf(finding), STYLE_PATTERNS)) {
            ReviewResult.Finding copy = new ReviewResult.Finding();
            copy.setSeverity("MINOR");
            copy.setFile(finding.getFile());
            copy.setLine(finding.getLine());
            copy.setIssue(finding.getIssue());
            copy.setSuggestion(finding.getSuggestion());
            return copy;
        }
        return finding;
    }

    private boolean hasBlockerOrMajor(ReviewResult.Finding finding) {
        String severity = severityOf(finding);
        return "BLOCKER".equals(severity) || "MAJOR".equals(severity);
    }

    private String severityOf(ReviewResult.Finding finding) {
        String severity = finding.getSeverity() == null ? "" : finding.getSeverity();
        return severity.toUpperCase(Locale.ROOT);
    }

    /** 匹配范围含 issue 与 suggestion，大小写不敏感。 */
    private String textOf(ReviewResult.Finding finding) {
        String issue = finding.getIssue() == null ? "" : finding.getIssue();
        String suggestion = finding.getSuggestion() == null ? "" : finding.getSuggestion();
        return (issue + " " + suggestion).toLowerCase(Locale.ROOT);
    }

    private boolean matchesAny(String text, String[] patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
