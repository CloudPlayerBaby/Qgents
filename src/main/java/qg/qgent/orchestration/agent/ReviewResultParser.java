package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.orchestration.result.ReviewResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把 LLM 返回的审查结果 JSON 解析并校验为结构化 {@link ReviewResult}。
 * <p>
 * 期望的 JSON 形状：
 * <pre>
 * {
 *   "success": true,
 *   "summary": "...",
 *   "findings": [{"file":"...","line":12,"severity":"MAJOR","issue":"...","suggestion":"..."}],
 *   "suggestions": ["..."],
 *   "needsCodingFix": true
 * }
 * </pre>
 * 校验规则：success 必须存在且为布尔，summary 非空；每个 finding 的 severity 必须属于
 * BLOCKER/MAJOR/MINOR/INFO，issue 非空；findings/suggestions 有数量上限。解析失败抛
 * {@link ReviewParseException}，由 ReviewAgent 按基础设施失败处理（同相位重试）。
 * BLOCKER/MAJOR 强制 FAIL 的最终判定由 ReviewAgent 依据 severity 策略给出，不在本类判定。
 */
public class ReviewResultParser {

    private static final int MAX_FINDINGS = 100;
    private static final int MAX_SUGGESTIONS = 50;
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR", "INFO");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 LLM 审查输出文本。
     *
     * @param raw LLM 输出文本，允许包在 ```json 围栏内。
     * @return 校验通过的结构化 ReviewResult。
     * @throws ReviewParseException 输出非法、缺必填字段或字段超限。
     */
    public ReviewResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ReviewParseException("review result is empty");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new ReviewParseException("review result is not valid JSON: " + e.getMessage());
        }
        return parse(node);
    }

    /**
     * 解析 LLM 审查输出的 finalResult 节点。
     *
     * @param node 已解析为 JSON 对象的 finalResult 节点。
     * @return 校验通过的结构化 ReviewResult。
     * @throws ReviewParseException 输出非法、缺必填字段或字段超限。
     */
    public ReviewResult parse(JsonNode node) {
        JsonNode successNode = node.get("success");
        if (successNode == null || !successNode.isBoolean()) {
            throw new ReviewParseException("review result missing required boolean 'success'");
        }
        String summary = optionalText(node, "summary");
        if (summary == null || summary.isBlank()) {
            throw new ReviewParseException("review result missing required non-empty 'summary'");
        }
        ReviewResult result = new ReviewResult();
        result.setSuccess(successNode.asBoolean());
        result.setSummary(summary);
        result.setFindings(parseFindings(node));
        result.setSuggestions(parseSuggestions(node));
        JsonNode fixNode = node.get("needsCodingFix");
        if (fixNode != null && fixNode.isBoolean()) {
            result.setNeedsCodingFix(fixNode.asBoolean());
        }
        return result;
    }

    private List<ReviewResult.Finding> parseFindings(JsonNode node) {
        JsonNode findingsNode = node.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            return List.of();
        }
        if (findingsNode.size() > MAX_FINDINGS) {
            throw new ReviewParseException("review result 'findings' exceeds " + MAX_FINDINGS);
        }
        List<ReviewResult.Finding> findings = new ArrayList<>();
        for (JsonNode item : findingsNode) {
            String severity = optionalText(item, "severity");
            if (severity == null) {
                throw new ReviewParseException("finding missing required 'severity'");
            }
            String normalized = severity.toUpperCase();
            if (!SEVERITIES.contains(normalized)) {
                throw new ReviewParseException("finding has illegal severity '" + severity
                        + "'; allowed: BLOCKER/MAJOR/MINOR/INFO");
            }
            String issue = optionalText(item, "issue");
            if (issue == null || issue.isBlank()) {
                throw new ReviewParseException("finding missing required non-empty 'issue'");
            }
            ReviewResult.Finding finding = new ReviewResult.Finding();
            finding.setSeverity(normalized);
            finding.setIssue(issue);
            finding.setFile(optionalText(item, "file"));
            JsonNode lineNode = item.get("line");
            if (lineNode != null && lineNode.isIntegralNumber()) {
                finding.setLine(lineNode.asInt());
            }
            finding.setSuggestion(optionalText(item, "suggestion"));
            findings.add(finding);
        }
        return findings;
    }

    private List<String> parseSuggestions(JsonNode node) {
        JsonNode suggestionsNode = node.get("suggestions");
        if (suggestionsNode == null || !suggestionsNode.isArray()) {
            return List.of();
        }
        if (suggestionsNode.size() > MAX_SUGGESTIONS) {
            throw new ReviewParseException("review result 'suggestions' exceeds " + MAX_SUGGESTIONS);
        }
        List<String> suggestions = new ArrayList<>();
        for (JsonNode item : suggestionsNode) {
            if (item.isTextual() && !item.asText().isBlank()) {
                suggestions.add(item.asText().trim());
            }
        }
        return suggestions;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

    /** 去掉常见的 ```json / ``` 围栏包裹。 */
    private String stripFences(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
