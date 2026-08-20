package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.orchestration.DeliveryMode;
import qg.qgent.orchestration.result.PlanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 把 LLM 返回的 Plan JSON 文本解析并校验为结构化 PlanResult。
 * <p>
 * 期望的 JSON 形状（两轮按需读取的计划轮）：
 * <pre>
 * {
 *   "taskUnderstanding": "...",
 *   "implementationGoals": ["..."],
 *   "steps": [{"title":"...", "files":["..."], "description":"...", "executionMode":"MUTATE|VERIFY", "requiredCapabilities":["java"], "suggestedAgentId":"..."}],
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
 * suggestedAgentId 为可选字段：仅接受合法 UUID，缺失/非法一律忽略（返回 null），
 * 池内归属校验发生在物化选人时（{@code AgentMatchDecider} 对池外先验不采信）。
 */
public class PlanResultParser {

    private static final int MAX_STEPS = 12;
    private static final int MAX_GOALS = 20;
    private static final int MAX_RISKS = 20;
    private static final int MAX_FILES_PER_STEP = 20;
    private static final int MAX_CAPABILITIES_PER_STEP = 12;
    private static final int MAX_ASSERTIONS_PER_STEP = 8;
    private static final int MAX_VERIFICATION_COMMANDS = 8;
    private static final Pattern CAPABILITY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> ASSERTION_TYPES = Set.of(
            "EXISTS", "EMPTY", "LINES_EQ", "LINES_GT", "LINES_LT", "CONTAINS", "NOT_CONTAINS");

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
        JsonNode node = toJson(raw);
        PlanResult plan = new PlanResult();

        plan.setTaskUnderstanding(requireText(node, "taskUnderstanding"));
        plan.setObjectives(requireStringArray(node, "implementationGoals", MAX_GOALS, "implementationGoals"));
        plan.setTestPlan(requireText(node, "testPlan"));
        plan.setVerificationMode(optionalVerificationMode(node, plan.getTestPlan()));
        plan.setImplementationSteps(parseSteps(node));
        plan.setRisks(optionalStringArray(node, "risks", MAX_RISKS));
        plan.setDeliveryMode(optionalDeliveryMode(node));
        plan.setScaleReason(optionalText(node, "scaleReason"));
        plan.setVerification(parseVerification(node));
        return plan;
    }

    /**
     * 可选的结构化验证命令解析：仅接受命中白名单模板的命令（{@link TestCommandResolver
     * #isAllowedVerificationCommand}）与合法相对路径的仓库目录；非法条目一律忽略（不抛
     * PlanParseException），缺失/非法不改变计划可用性（与 machineAssertions 同款"可选字段
     * 非法不阻断"原则）。至多保留 {@link #MAX_VERIFICATION_COMMANDS} 条。
     */
    private PlanResult.Verification parseVerification(JsonNode node) {
        JsonNode verificationNode = node.get("verification");
        if (verificationNode == null || !verificationNode.isObject()) {
            return null;
        }
        JsonNode commandsNode = verificationNode.get("commands");
        if (commandsNode == null || !commandsNode.isArray()) {
            return null;
        }
        PlanResult.Verification verification = new PlanResult.Verification();
        List<PlanResult.VerificationCommand> accepted = new ArrayList<>();
        for (JsonNode item : commandsNode) {
            if (!item.isObject()) {
                continue;
            }
            PlanResult.VerificationCommand entry = new PlanResult.VerificationCommand();
            String repositoryPath = optionalText(item, "repositoryPath");
            if (repositoryPath != null && !repositoryPath.isBlank()) {
                if (!isRelativePath(repositoryPath)) {
                    continue;
                }
                entry.setRepositoryPath(repositoryPath);
            }
            JsonNode commandNode = item.get("command");
            if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
                continue;
            }
            List<String> command = new ArrayList<>();
            for (JsonNode token : commandNode) {
                if (!token.isTextual() || token.asText().isBlank()) {
                    command.clear();
                    break;
                }
                command.add(token.asText().trim());
            }
            if (command.isEmpty() || !TestCommandResolver.isAllowedVerificationCommand(command)) {
                continue;
            }
            entry.setCommand(command);
            accepted.add(entry);
            if (accepted.size() >= MAX_VERIFICATION_COMMANDS) {
                break;
            }
        }
        if (accepted.isEmpty()) {
            return null;
        }
        verification.setCommands(accepted);
        return verification;
    }

    private JsonNode toJson(String text) {
        try {
            return JsonTextExtractor.parseObject(objectMapper, text);
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
            step.setExecutionMode(optionalExecutionMode(stepNode, step.getTitle(), step.getDescription()));
            step.setRequiredCapabilities(optionalCapabilities(stepNode));
            step.setSuggestedAgentId(optionalStepAgentId(stepNode));
            step.setAcceptanceNotes(optionalText(stepNode, "acceptanceNotes"));
            step.setMachineAssertions(parseAssertions(stepNode));
            steps.add(step);
        }
        return steps;
    }

    private String optionalExecutionMode(JsonNode stepNode, String title, String description) {
        String value = optionalText(stepNode, "executionMode");
        if (value != null && !value.isBlank()) {
            String normalized = value.toUpperCase(Locale.ROOT);
            if (!normalized.equals("MUTATE") && !normalized.equals("VERIFY")) {
                throw new PlanParseException("plan step executionMode must be MUTATE or VERIFY");
            }
            return normalized;
        }
        String text = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        boolean verificationWord = text.matches(".*(verify|verification|check|validate|test|inspect|review|验证|检查|核验|测试|审查).*");
        boolean mutationWord = text.matches(".*(create|add|modify|change|implement|write|update|fix|新建|创建|新增|修改|实现|编写|更新|修复).*");
        boolean explicitlyReadOnly = text.matches(".*(不|无需|不要|不得).{0,5}(修改|创建|写入|变更|落地).*");
        // “验证新增文件”中的“新增”是被核验对象，不是当前步骤的写动作；只有
        // 明确出现“验证并修复/检查后创建”等串联动作时，才把验证词推断为 MUTATE。
        boolean verificationFirst = text.matches("^(verify|verification|check|validate|test|inspect|review|验证|检查|核验|测试|审查).*");
        boolean mutationAfterVerification = text.matches(".*(verify|verification|check|validate|test|inspect|review|验证|检查|核验|测试|审查).*(and|then|并|然后|同时|后).*(create|add|modify|change|implement|write|update|fix|新建|创建|新增|修改|实现|编写|更新|修复).*");
        if (explicitlyReadOnly || (verificationFirst && !mutationAfterVerification)) {
            return "VERIFY";
        }
        return mutationWord ? "MUTATE" : (verificationWord ? "VERIFY" : "MUTATE");
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
     * 可选的建议 Agent id：仅接受合法 UUID 字符串；缺失 / 空白 / 非法一律返回 null（忽略）。
     * 解析器不感知候选池（池内校验发生在物化选人时），只做语法级收敛。
     */
    private UUID optionalStepAgentId(JsonNode stepNode) {
        String value = optionalText(stepNode, "suggestedAgentId");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 可选的结构化断言解析：仅接受白名单类型 + 合法相对路径 + 必填参数完备的条目。
     * 非法条目一律忽略（不抛 PlanParseException），避免模型的断言噪声阻断计划——断言是
     * 预期信号而非裁决，缺失/非法不改变计划可用性（与 suggestedAgentId 同款"可选字段
     * 非法不阻断"原则）。每步至多保留 {@link #MAX_ASSERTIONS_PER_STEP} 条。
     */
    private List<PlanResult.Assertion> parseAssertions(JsonNode stepNode) {
        JsonNode assertionsNode = stepNode.get("machineAssertions");
        if (assertionsNode == null || !assertionsNode.isArray()) {
            return List.of();
        }
        List<PlanResult.Assertion> result = new ArrayList<>();
        for (JsonNode item : assertionsNode) {
            if (!item.isObject()) {
                continue;
            }
            String type = optionalText(item, "type");
            String normalized = type == null ? null : type.toUpperCase(Locale.ROOT);
            if (normalized == null || !ASSERTION_TYPES.contains(normalized)) {
                continue;
            }
            String file = optionalText(item, "file");
            if (file == null || !isRelativePath(file)) {
                continue;
            }
            String value = optionalText(item, "value");
            boolean needsValue = normalized.startsWith("LINES_")
                    || normalized.equals("CONTAINS") || normalized.equals("NOT_CONTAINS");
            if (needsValue && (value == null || value.isBlank())) {
                continue;
            }
            if (normalized.startsWith("LINES_")) {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            PlanResult.Assertion assertion = new PlanResult.Assertion();
            assertion.setType(normalized);
            assertion.setFile(file);
            assertion.setValue(value);
            result.add(assertion);
            if (result.size() >= MAX_ASSERTIONS_PER_STEP) {
                break;
            }
        }
        return result;
    }

    /**
     * 可选交付模式：仅接受 DIFF_FIRST/MR_FIRST；缺失或非法视为未判定返回 null（硬规则兜底）。
     */
    private String optionalDeliveryMode(JsonNode node) {
        String value = optionalText(node, "deliveryMode");
        return DeliveryMode.isValid(value) ? value : null;
    }

    /** Backward-compatible inference for plans created before verificationMode existed. */
    private String optionalVerificationMode(JsonNode node, String testPlan) {
        String value = optionalText(node, "verificationMode");
        if ("MANUAL".equalsIgnoreCase(value)) {
            return "MANUAL";
        }
        if ("AUTOMATED".equalsIgnoreCase(value)) {
            return "AUTOMATED";
        }
        String text = testPlan == null ? "" : testPlan.toLowerCase(Locale.ROOT);
        if (text.contains("人工核验") || text.contains("不运行自动化")
                || text.contains("无需自动化") || text.contains("纯检查")
                || text.contains("manual")) {
            return "MANUAL";
        }
        return "AUTOMATED";
    }

}
