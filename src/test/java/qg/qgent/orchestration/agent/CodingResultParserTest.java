package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.result.CodingResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CodingResultParser 纯单元测试：deviations 可选字段的解析、缺省回退与上限校验。
 * 其余必填字段（success/summary）的行为由既有 GenericResultParserTest / CodingAgentTest 覆盖。
 */
class CodingResultParserTest {

    private final CodingResultParser parser = new CodingResultParser();

    @Test
    void parsesDeviationsFromLegacyShape() {
        CodingResult result = parser.parse("""
                {"success": true, "summary": "已追加两行配置",
                 "deviations": ["计划要求追加 1 行，实际追加 2 行：用户指令'再加一点内容'语义更符合多写"]}
                """);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDeviations()).containsExactly(
                "计划要求追加 1 行，实际追加 2 行：用户指令'再加一点内容'语义更符合多写");
    }

    @Test
    void parsesDeviationsFromNativeShape() {
        CodingResult result = parser.parse("""
                {"finalResult": {"success": true, "summary": "done",
                 "deviations": ["重构了包名：原包名与实际模块职责不符"]}}
                """);
        assertThat(result.getDeviations()).containsExactly("重构了包名：原包名与实际模块职责不符");
    }

    @Test
    void missingDeviationsFallsBackToEmpty() {
        CodingResult result = parser.parse("{\"success\": true, \"summary\": \"done\"}");
        assertThat(result.getDeviations()).isEmpty();
    }

    @Test
    void blankDeviationEntriesAreFiltered() {
        CodingResult result = parser.parse("""
                {"success": true, "summary": "done", "deviations": ["有理由的偏差", "  ", ""]}
                """);
        assertThat(result.getDeviations()).containsExactly("有理由的偏差");
    }

    @Test
    void deviationsOverLimitAreRejectedAsProtocolError() {
        String items = java.util.stream.IntStream.range(0, 201)
                .mapToObj(i -> "\"d" + i + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String json = "{\"success\": true, \"summary\": \"done\", \"deviations\": [" + items + "]}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(CodingParseException.class);
    }
}
