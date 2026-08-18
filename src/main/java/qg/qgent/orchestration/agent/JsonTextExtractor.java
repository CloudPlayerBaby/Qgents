package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从模型最终文本中提取完整的 JSON 对象。
 *
 * <p>模型有时会在 JSON 前后添加说明，或使用 Markdown 代码围栏。这里不使用正则匹配大括号，
 * 而是按 JSON 字符串、转义字符和嵌套层级扫描，避免在嵌套对象或字符串包含大括号时截断。
 * 该类只负责语法层提取，业务字段校验仍由各 Agent 的专用 Parser 完成。</p>
 */
public final class JsonTextExtractor {

    private static final int MAX_INPUT_CHARS = 128_000;

    private JsonTextExtractor() {
    }

    /**
     * 解析模型文本中的 JSON 对象。先尝试全文解析，再从完整对象边界中寻找候选对象。
     *
     * @param objectMapper JSON 解析器
     * @param raw          模型原始文本
     * @return JSON 对象节点
     * @throws IllegalArgumentException 文本为空、过大或不包含合法 JSON 对象
     */
    public static JsonNode parseObject(ObjectMapper objectMapper, String raw) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JSON response is empty");
        }
        String text = raw.strip();
        if (text.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("JSON response exceeds " + MAX_INPUT_CHARS + " characters");
        }

        JsonNode direct = tryReadObject(objectMapper, text);
        if (direct != null) {
            return direct;
        }

        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') {
                continue;
            }
            // 只接受结构层级的顶层对象。否则像 `[ {"success": true} ]` 或
            // 残缺外层对象里的嵌套对象会被误当作 Agent 结果。
            if (!isTopLevelObject(text, start)) {
                continue;
            }
            int end = matchingObjectEnd(text, start);
            if (end < 0) {
                continue;
            }
            JsonNode candidate = tryReadObject(objectMapper, text.substring(start, end + 1));
            if (candidate != null) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("response does not contain a valid JSON object");
    }

    private static JsonNode tryReadObject(ObjectMapper objectMapper, String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 返回从 start 开始的大括号对应的结束位置；字符串内容中的大括号不参与层级计算。
     */
    private static int matchingObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isTopLevelObject(String text, int start) {
        int objectDepth = 0;
        int arrayDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < start; i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                objectDepth++;
            } else if (current == '}' && objectDepth > 0) {
                objectDepth--;
            } else if (current == '[') {
                arrayDepth++;
            } else if (current == ']' && arrayDepth > 0) {
                arrayDepth--;
            }
        }
        return objectDepth == 0 && arrayDepth == 0;
    }
}
