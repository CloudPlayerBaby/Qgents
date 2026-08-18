package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.orchestration.result.CodingResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 LLM 返回的 finalResult JSON 解析并校验为结构化 {@link CodingResult}。
 * <p>
 * 期望的 JSON 形状：
 * <pre>
 * {
 *   "success": true,
 *   "summary": "...",
 *   "modifiedFiles": ["相对路径"],
 *   "changes": ["变更说明"],
 *   "errors": ["错误说明"]
 * }
 * </pre>
 * 校验规则：success 必须存在且为布尔；success=true 时 summary 非空。
 * 校验失败抛 {@link CodingParseException}，由 CodingAgent 转为 FAILED_INFRASTRUCTURE。
 */
public class CodingResultParser {

    private static final int MAX_ITEMS = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 LLM 输出文本为结构化 CodingResult，兼容两种形状：
     * <ul>
     *   <li>{@code {"success": ..., ...}}（legacy 直接输出 finalResult 内容）；</li>
     *   <li>{@code {"finalResult": {"success": ..., ...}}}（原生协议，finalResult 包裹）。</li>
     * </ul>
     * 输出允许包在 ```json 围栏内。形状不匹配抛 {@link CodingParseException}（协议错误）。
     *
     * @param raw LLM 输出文本。
     * @return 校验通过的结构化 CodingResult。
     * @throws CodingParseException 输出非法或缺必填字段。
     */
    public CodingResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CodingParseException("coding result is empty");
        }
        JsonNode node;
        try {
            node = JsonTextExtractor.parseObject(objectMapper, raw);
        } catch (Exception e) {
            throw new CodingParseException("coding result is not valid JSON: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new CodingParseException("coding result is not a JSON object");
        }
        JsonNode finalResult = node.get("finalResult");
        return parse(finalResult != null && finalResult.isObject() ? finalResult : node);
    }

    /**
     * 解析 finalResult 节点（调用方已做 JSON 解析与围栏剥离）。
     *
     * @param node finalResult 对象节点。
     * @return 校验通过的结构化 CodingResult。
     * @throws CodingParseException finalResult 非法或不完整。
     */
    public CodingResult parse(JsonNode node) {
        JsonNode successNode = node.get("success");
        if (successNode == null || !successNode.isBoolean()) {
            throw new CodingParseException("coding finalResult missing required boolean 'success'");
        }
        CodingResult result = new CodingResult();
        result.setSuccess(successNode.asBoolean());
        result.setSummary(optionalText(node, "summary"));
        result.setModifiedFiles(optionalStringArray(node, "modifiedFiles"));
        result.setChanges(optionalStringArray(node, "changes"));
        result.setErrors(optionalStringArray(node, "errors"));
        if (result.isSuccess() && (result.getSummary() == null || result.getSummary().isBlank())) {
            throw new CodingParseException("coding finalResult success=true requires non-empty 'summary'");
        }
        return result;
    }

    private List<String> optionalStringArray(JsonNode node, String field) {
        JsonNode arrayNode = node.get(field);
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        if (arrayNode.size() > MAX_ITEMS) {
            throw new CodingParseException("coding finalResult field '" + field + "' exceeds " + MAX_ITEMS);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

}
