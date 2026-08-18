package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTextExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsNestedObjectFromMarkdownAndSurroundingText() {
        String raw = "审查结果如下：\n```json\n"
                + "{\"summary\":\"保留 {x}\",\"nested\":{\"ok\":true}}\n"
                + "```\n以上。";

        JsonNode result = JsonTextExtractor.parseObject(objectMapper, raw);

        assertThat(result.path("summary").asText()).isEqualTo("保留 {x}");
        assertThat(result.path("nested").path("ok").asBoolean()).isTrue();
    }

    @Test
    void rejectsTextWithoutCompleteObject() {
        assertThatThrownBy(() -> JsonTextExtractor.parseObject(objectMapper, "结果：{\"success\":true"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsJsonArrayBecauseAgentResultsMustBeObjects() {
        assertThatThrownBy(() -> JsonTextExtractor.parseObject(objectMapper, "[ {\"success\":true} ]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsObjectAfterPunctuationInSurroundingText() {
        JsonNode result = JsonTextExtractor.parseObject(objectMapper, "说明，结果是： {\"success\":true}");

        assertThat(result.path("success").asBoolean()).isTrue();
    }

    @Test
    void doesNotExtractNestedObjectFromIncompleteOuterObject() {
        assertThatThrownBy(() -> JsonTextExtractor.parseObject(objectMapper,
                "结果：{\"wrapper\":{\"success\":true}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
