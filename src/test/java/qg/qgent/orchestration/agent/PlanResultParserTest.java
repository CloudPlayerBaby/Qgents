package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.result.PlanResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlanResultParser 纯单元测试：合法响应映射、markdown 围栏容忍、非法/不完整响应拒绝。
 */
class PlanResultParserTest {

    private final PlanResultParser parser = new PlanResultParser();

    private static final String VALID_JSON = """
            {
              "taskUnderstanding": "understand",
              "implementationGoals": ["goal1", "goal2"],
              "steps": [{"title":"impl","files":["a.java","b.java"],"description":"do it","requiredCapabilities":["java","spring-boot"]}],
              "testPlan": "run tests",
              "risks": ["risk1"]
            }
            """;

    @Test void parsesValidPlanResponse() {
        PlanResult plan = parser.parse(VALID_JSON);
        assertThat(plan.getTaskUnderstanding()).isEqualTo("understand");
        assertThat(plan.getObjectives()).containsExactly("goal1", "goal2");
        assertThat(plan.getImplementationSteps()).hasSize(1);
        assertThat(plan.getImplementationSteps().get(0).getTitle()).isEqualTo("impl");
        assertThat(plan.getImplementationSteps().get(0).getFiles()).containsExactly("a.java", "b.java");
        assertThat(plan.getImplementationSteps().get(0).getDescription()).isEqualTo("do it");
        assertThat(plan.getImplementationSteps().get(0).getRequiredCapabilities()).containsExactly("java", "spring-boot");
        assertThat(plan.getTestPlan()).isEqualTo("run tests");
        assertThat(plan.getRisks()).containsExactly("risk1");
    }

    @Test void acceptsJsonFencedInMarkdown() {
        PlanResult plan = parser.parse("```json\n" + VALID_JSON + "\n```");
        assertThat(plan.getObjectives()).containsExactly("goal1", "goal2");
        assertThat(plan.getImplementationSteps()).hasSize(1);
    }

    @Test void toleratesMissingOptionalRisks() {
        PlanResult plan = parser.parse(VALID_JSON.replace("\"risks\": [\"risk1\"]", "\"risks\": []"));
        assertThat(plan.getRisks()).isEmpty();
    }

    @Test void rejectsEmptyResponse() {
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsNonJson() {
        assertThatThrownBy(() -> parser.parse("hello world")).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsMissingGoals() {
        String json = "{\"taskUnderstanding\":\"x\",\"steps\":[{\"title\":\"s\",\"files\":[\"a\"]}],\"testPlan\":\"t\"}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsMissingSteps() {
        String json = "{\"taskUnderstanding\":\"x\",\"implementationGoals\":[\"g\"],\"testPlan\":\"t\"}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsStepWithoutFiles() {
        String json = "{\"taskUnderstanding\":\"x\",\"implementationGoals\":[\"g\"],\"steps\":[{\"title\":\"s\"}],\"testPlan\":\"t\"}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsBlankTestPlan() {
        String json = "{\"taskUnderstanding\":\"x\",\"implementationGoals\":[\"g\"],\"steps\":[{\"title\":\"s\",\"files\":[\"a\"]}],\"testPlan\":\"\"}";
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsAbsoluteOrTraversalFilePath() {
        String absolute = VALID_JSON.replace("a.java", "/etc/passwd");
        String traversal = VALID_JSON.replace("a.java", "../outside.java");
        assertThatThrownBy(() -> parser.parse(absolute)).isInstanceOf(PlanParseException.class);
        assertThatThrownBy(() -> parser.parse(traversal)).isInstanceOf(PlanParseException.class);
    }

    @Test void rejectsInvalidCapabilityTag() {
        String json = VALID_JSON.replace("spring-boot", "Spring Boot");
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(PlanParseException.class);
    }
}
