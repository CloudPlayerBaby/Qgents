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
        String stripped = stripFences(raw);
        try {
            return objectMapper.readTree(stripped);
        } catch (Exception e) {
            JsonNode extracted = tryExtractJsonObject(stripped);
            if (extracted != null) {
                return extracted;
            }
            throw new GenericParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "custom agent output is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * 模型偶尔在 JSON 对象前后夹杂说明文字；尝试定位首个 '{' 与最后一个 '}' 之间的子串再解析。
     * 提取失败返回 null（仍由调用方判定为非法输出，不伪造结果）。
     */
    private JsonNode tryExtractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception ignore) {
            return null;
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
