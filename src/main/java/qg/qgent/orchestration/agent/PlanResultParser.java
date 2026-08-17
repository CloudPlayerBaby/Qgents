package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.orchestration.DeliveryMode;
import qg.qgent.orchestration.result.PlanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把 LLM 返回的 Plan JSON 文本解析并校验为结构化 PlanResult。
 * <p>
 * 期望的 JSON 形状（两轮按需读取的计划轮）：
 * <pre>
 * {
 *   "taskUnderstanding": "...",
 *   "implementationGoals": ["..."],
 *   "steps": [{"title":"...", "files":["..."], "description":"..."}],
 *   "testPlan": "...",
 *   "risks": ["..."],
 *   "deliveryMode": "DIFF_FIRST",
 *   "scaleReason": "..."
 * }
 * </pre>
 * 必填校验：taskUnderstanding/testPlan 非空，implementationGoals 与 steps 非空，
 * 且每个 step 必须有 title 和至少一个文件。校验失败抛 {@link PlanParseException}，
 * 由 PlanAgent 转为 FAILED_INFRASTRUCTURE。deliveryMode 为可选字段：仅接受
 * DIFF_FIRST/MR_FIRST，缺失或非法视为未判定（返回 null），由硬规则兜底，不阻断计划。
 */
public class PlanResultParser {

    private static final int MAX_STEPS = 12;
    private static final int MAX_GOALS = 20;
    private static final int MAX_RISKS = 20;
    private static final int MAX_FILES_PER_STEP = 20;
    private static final int MAX_CAPABILITIES_PER_STEP = 12;
    private static final Pattern CAPABILITY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析并校验 LLM 返回的计划文本。
     *
     * @param raw LLM 输出文本，允许包在 ```json 围栏内。
     * @return 校验通过的结构化 PlanResult。
     * @throws PlanParseException 文本非法或不完整。
     */
    public PlanResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PlanParseException("plan response is empty");
        }
        JsonNode node = toJson(stripFences(raw));
        PlanResult plan = new PlanResult();

        plan.setTaskUnderstanding(requireText(node, "taskUnderstanding"));
        plan.setObjectives(requireStringArray(node, "implementationGoals", MAX_GOALS, "implementationGoals"));
        plan.setTestPlan(requireText(node, "testPlan"));
        plan.setImplementationSteps(parseSteps(node));
        plan.setRisks(optionalStringArray(node, "risks", MAX_RISKS));
        plan.setDeliveryMode(optionalDeliveryMode(node));
        plan.setScaleReason(optionalText(node, "scaleReason"));
        return plan;
    }

    private JsonNode toJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new PlanParseException("plan response is not valid JSON: " + e.getMessage());
        }
    }

    private List<PlanResult.ImplementationStep> parseSteps(JsonNode node) {
        JsonNode stepsNode = node.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new PlanParseException("plan response missing required field 'steps'");
        }
        if (stepsNode.size() > MAX_STEPS) {
            throw new PlanParseException("plan response 'steps' exceeds " + MAX_STEPS);
        }
        List<PlanResult.ImplementationStep> steps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
            step.setTitle(requireStepText(stepNode, "title"));
            step.setFiles(requireStepFiles(stepNode));
            step.setDescription(optionalText(stepNode, "description"));
            step.setRequiredCapabilities(optionalCapabilities(stepNode));
            steps.add(step);
        }
        return steps;
    }

    private String requireText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new PlanParseException("plan response missing required field '" + field + "'");
        }
        return value;
    }

    private String requireStepText(JsonNode stepNode, String field) {
        String value = optionalText(stepNode, field);
        if (value == null || value.isBlank()) {
            throw new PlanParseException("plan step missing required field '" + field + "'");
        }
        return value;
    }

    private List<String> requireStepFiles(JsonNode stepNode) {
        JsonNode filesNode = stepNode.get("files");
        if (filesNode == null || !filesNode.isArray() || filesNode.isEmpty()) {
            throw new PlanParseException("plan step missing required non-empty 'files'");
        }
        List<String> files = new ArrayList<>();
        for (JsonNode fileNode : filesNode) {
            String file = fileNode.isTextual() ? fileNode.asText().trim() : "";
            if (!file.isBlank() && isRelativePath(file)) {
                files.add(file);
            } else if (!file.isBlank()) {
                throw new PlanParseException("plan step file must be a relative normalized path");
            }
            if (files.size() > MAX_FILES_PER_STEP) {
                throw new PlanParseException("plan step 'files' exceeds " + MAX_FILES_PER_STEP);
            }
        }
        if (files.isEmpty()) {
            throw new PlanParseException("plan step has empty 'files'");
        }
        return files;
    }

    private List<String> optionalCapabilities(JsonNode stepNode) {
        JsonNode values = stepNode.get("requiredCapabilities");
        if (values == null) {
            return List.of();
        }
        if (!values.isArray() || values.size() > MAX_CAPABILITIES_PER_STEP) {
            throw new PlanParseException("plan step 'requiredCapabilities' is invalid or exceeds "
                    + MAX_CAPABILITIES_PER_STEP);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String capability = value.isTextual() ? value.asText().trim().toLowerCase(Locale.ROOT) : "";
            if (!CAPABILITY.matcher(capability).matches()) {
                throw new PlanParseException("plan step capability must be lowercase kebab-case");
            }
            if (!result.contains(capability)) {
                result.add(capability);
            }
        }
        return result;
    }

    private boolean isRelativePath(String path) {
        return !path.startsWith("/") && !path.startsWith("\\") && !path.matches("^[A-Za-z]:.*")
                && !path.contains("\\") && !path.contains("..") && path.length() <= 512;
    }

    private List<String> requireStringArray(JsonNode node, String field, int max, String label) {
        List<String> values = optionalStringArray(node, field, max);
        if (values.isEmpty()) {
            throw new PlanParseException("plan response missing required field '" + field + "'");
        }
        return values;
    }

    private List<String> optionalStringArray(JsonNode node, String field, int max) {
        JsonNode arrayNode = node.get(field);
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        if (arrayNode.size() > max) {
            throw new PlanParseException("plan response field '" + field + "' exceeds " + max);
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

    /**
     * 可选交付模式：仅接受 DIFF_FIRST/MR_FIRST；缺失或非法视为未判定返回 null（硬规则兜底）。
     */
    private String optionalDeliveryMode(JsonNode node) {
        String value = optionalText(node, "deliveryMode");
        return DeliveryMode.isValid(value) ? value : null;
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
