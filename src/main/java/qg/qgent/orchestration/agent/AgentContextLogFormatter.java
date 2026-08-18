package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.orchestration.tool.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

/** 脱敏记录 Agent 上下文摘要和受限调试片段。 */
public final class AgentContextLogFormatter {
    private static final int DEFAULT_SAMPLE_LIMIT = 240;

    private AgentContextLogFormatter() {
    }

    /** 生成 INFO 日志使用的数量、长度和摘要哈希，不输出原始上下文。 */
    public static String summary(AgentInput input) {
        if (input == null) {
            return "input=null";
        }
        int conversationCount = size(input.getConversation());
        int conversationChars = input.getConversation() == null ? 0
                : input.getConversation().stream().mapToInt(message -> length(message == null ? null : message.getText())).sum();
        int memoryCount = size(input.getMemories());
        int memoryChars = input.getMemories() == null ? 0 : input.getMemories().stream().mapToInt(memory ->
                length(memory == null ? null : memory.getTitle()) + length(memory == null ? null : memory.getContent())).sum();
        return "taskId=" + input.getTaskId()
                + " projectId=" + input.getProjectId()
                + " phase=" + input.getPhase()
                + " taskRunId=" + input.getTaskRunId()
                + " taskStepId=" + input.getTaskStepId()
                + " workspaceId=" + input.getWorkspaceId()
                + " taskTitleChars=" + length(input.getTaskTitle())
                + " requirementChars=" + length(input.getRequirement())
                + " instructionChars=" + length(input.getInstruction())
                + " feedbackChars=" + length(input.getFeedback())
                + " requirementTitleChars=" + length(input.getRequirementTitle())
                + " requirementDescriptionChars=" + length(input.getRequirementDescription())
                + " workspaceSummaryChars=" + length(input.getWorkspaceSummary())
                + " conversationCount=" + conversationCount
                + " conversationChars=" + conversationChars
                + " conversationSha256=" + hashConversation(input.getConversation())
                + " skillsCount=" + size(input.getSkills())
                + " memoriesCount=" + memoryCount
                + " memoryChars=" + memoryChars
                + " memorySha256=" + hashMemories(input.getMemories())
                + " planPresent=" + (input.getPlanResult() != null)
                + " codingResultPresent=" + (input.getCodingResult() != null)
                + " testResultPresent=" + (input.getTestResult() != null);
    }

    /** 生成仅供 DEBUG 使用的限长、脱敏上下文片段。 */
    public static String samples(AgentInput input) {
        return samples(input, DEFAULT_SAMPLE_LIMIT);
    }

    /** @param maxChars 每个字段最多输出的字符数 */
    public static String samples(AgentInput input, int maxChars) {
        if (input == null) {
            return "input=null";
        }
        StringJoiner joiner = new StringJoiner(" | ");
        add(joiner, "taskTitle", input.getTaskTitle(), maxChars);
        add(joiner, "requirement", input.getRequirement(), maxChars);
        add(joiner, "instruction", input.getInstruction(), maxChars);
        add(joiner, "requirementTitle", input.getRequirementTitle(), maxChars);
        add(joiner, "requirementDescription", input.getRequirementDescription(), maxChars);
        if (input.getConversation() != null) {
            input.getConversation().stream().limit(3).forEach(message -> {
                if (message != null) {
                    add(joiner, "conversation[" + message.getSequence() + "]", message.getText(), maxChars);
                }
            });
        }
        return joiner.toString();
    }

    /** 生成工作区文件树摘要，不记录文件内容。 */
    public static String fileTreeSummary(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "count=0 sha256=" + hash("");
        }
        return "count=" + files.size() + " sha256=" + hash(String.join("\n", files));
    }

    private static void add(StringJoiner joiner, String name, String value, int maxChars) {
        if (value == null || value.isBlank()) {
            joiner.add(name + "=<empty>");
            return;
        }
        String sanitized = ExecutionContentSanitizer.sanitize(value).replace('\n', ' ').replace('\r', ' ');
        int limit = Math.max(1, maxChars);
        joiner.add(name + "=" + (sanitized.length() > limit ? sanitized.substring(0, limit) + "..." : sanitized));
    }

    private static String hashConversation(List<qg.qgent.dto.ContextMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return hash("");
        }
        String joined = messages.stream().map(message -> message == null ? ""
                : String.valueOf(message.getSequence()) + ":" + String.valueOf(message.getType()) + ":"
                        + String.valueOf(message.getText())).reduce((left, right) -> left + "\n" + right).orElse("");
        return hash(joined);
    }

    private static String hashMemories(List<qg.qgent.dto.ContextMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return hash("");
        }
        String joined = memories.stream().map(memory -> memory == null ? ""
                : String.valueOf(memory.getTitle()) + ":" + String.valueOf(memory.getContent()))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return hash(joined);
    }

    private static String hash(String value) {
        return Sha256.hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
