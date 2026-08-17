package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析自定义 Agent 的最终 JSON 输出为 {@link CustomResult}。
 * 处理常见 ```json / ``` 围栏包裹；非法输出（非 JSON、非对象、缺布尔 success）抛
 * {@link GenericParseException}（LLM_TOOL_CALL_MALFORMED）。summary/message 为可空文本。
 */
public class GenericResultParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public CustomResult parse(String raw) {
        JsonNode node = toJson(raw);
        if (node == null || !node.isObject() || !node.has("success") || !node.get("success").isBoolean()) {
            throw new GenericParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "custom result must be a JSON object with boolean 'success'");
        }
        boolean success = node.get("success").asBoolean();
        String summary = node.path("summary").isTextual() ? node.path("summary").asText() : null;
        String message = node.path("message").isTextual() ? node.path("message").asText() : null;
        return new CustomResult(success, summary, message);
    }

    private JsonNode toJson(String raw) {
        try {
            return objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new GenericParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "custom agent output is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * 去掉常见的 ```json / ``` 围栏包裹。
     */
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
