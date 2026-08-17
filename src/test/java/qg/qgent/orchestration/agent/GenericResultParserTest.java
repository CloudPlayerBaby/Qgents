package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自定义 Agent 最终输出解析契约：标准 JSON / 围栏剥离 / 夹杂说明文字时提取 JSON 对象 /
 * 缺 success 字段或非布尔 success / 纯文本无 JSON 一律抛 {@link GenericParseException}。
 */
class GenericResultParserTest {

    private final GenericResultParser parser = new GenericResultParser();

    @Test
    void parsesPlainJsonObject() {
        CustomResult result = parser.parse("{\"success\":true,\"summary\":\"s\",\"message\":\"m\"}");
        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("s");
        assertThat(result.message()).isEqualTo("m");
    }

    @Test
    void stripsJsonFences() {
        CustomResult result = parser.parse("```json\n{\"success\":false,\"summary\":\"s\"}\n```");
        assertThat(result.success()).isFalse();
        assertThat(result.summary()).isEqualTo("s");
    }

    @Test
    void extractsJsonObjectEmbeddedInSurroundingText() {
        CustomResult result = parser.parse("已完成，结果如下：{\"success\":true,\"summary\":\"done\"}，如有问题请告知。");
        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("done");
    }

    @Test
    void missingSuccessFieldStillThrows() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"no success\"}"))
                .isInstanceOf(GenericParseException.class)
                .hasMessageContaining(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
    }

    @Test
    void nonBooleanSuccessStillThrows() {
        assertThatThrownBy(() -> parser.parse("{\"success\":\"yes\"}"))
                .isInstanceOf(GenericParseException.class);
    }

    @Test
    void pureTextWithoutJsonStillThrows() {
        assertThatThrownBy(() -> parser.parse("功能已实现，测试通过。"))
                .isInstanceOf(GenericParseException.class);
    }
}
