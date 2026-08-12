package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.orchestration.result.TestResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 LLM 返回的测试分析 JSON 解析并校验为结构化 {@link TestResult}。
 * <p>
 * 期望的 JSON 形状：
 * <pre>
 * {
 *   "success": true,
 *   "summary": "...",
 *   "failures": [{"name":"...","reason":"...","severity":"ERROR"}],
 *   "needsCodingFix": true
 * }
 * </pre>
 * 校验规则：success 必须存在且为布尔，summary 非空。解析失败抛 {@link TestParseException}，
 * 由 TestAgent 退回基于真实 exit code 的结果，不影响 PASS/FAIL 真实性。
 */
public class TestResultParser {

    private static final int MAX_FAILURES = 100;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 LLM 的分析结果。exitCode/command/stdout/stderr 由 TestAgent 依据真实执行结果回填，
     * 本类不负责这些字段。
     *
     * @param raw LLM 输出文本，允许包在 ```json 围栏内。
     * @return 校验通过的结构化 TestResult。
     * @throws TestParseException 分析文本非法或不完整。
     */
    public TestResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TestParseException("test analysis is empty");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new TestParseException("test analysis is not valid JSON: " + e.getMessage());
        }
        JsonNode successNode = node.get("success");
        if (successNode == null || !successNode.isBoolean()) {
            throw new TestParseException("test analysis missing required boolean 'success'");
        }
        String summary = optionalText(node, "summary");
        if (summary == null || summary.isBlank()) {
            throw new TestParseException("test analysis missing required non-empty 'summary'");
        }
        TestResult result = new TestResult();
        result.setSuccess(successNode.asBoolean());
        result.setSummary(summary);
        result.setFailures(parseFailures(node));
        JsonNode fixNode = node.get("needsCodingFix");
        if (fixNode != null && fixNode.isBoolean()) {
            result.setNeedsCodingFix(fixNode.asBoolean());
        }
        return result;
    }

    private List<TestResult.Failure> parseFailures(JsonNode node) {
        JsonNode failuresNode = node.get("failures");
        if (failuresNode == null || !failuresNode.isArray()) {
            return List.of();
        }
        if (failuresNode.size() > MAX_FAILURES) {
            throw new TestParseException("test analysis 'failures' exceeds " + MAX_FAILURES);
        }
        List<TestResult.Failure> failures = new ArrayList<>();
        for (JsonNode item : failuresNode) {
            TestResult.Failure failure = new TestResult.Failure();
            failure.setName(optionalText(item, "name"));
            failure.setReason(optionalText(item, "reason"));
            failure.setSeverity(optionalText(item, "severity"));
            failures.add(failure);
        }
        return failures;
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
