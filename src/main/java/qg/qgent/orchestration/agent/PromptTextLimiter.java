package qg.qgent.orchestration.agent;

/**
 * 仅对发送给模型的文本副本做确定性头尾裁剪，避免大文件树、日志或文件内容挤占上下文。
 * 原始 Workspace 内容与工具执行结果不会经过此类修改。
 */
public final class PromptTextLimiter {

    static final String TRUNCATION_MARKER = "\n...[已裁剪]...\n";

    private PromptTextLimiter() {
    }

    /**
     * 将文本限制在指定字符数内；超限时保留首尾并插入显式裁剪标记。
     */
    public static String limitHeadTail(String value, int maxChars) {
        return limitHeadTail(value, maxChars, TRUNCATION_MARKER);
    }

    public static String limitHeadTail(String value, int maxChars, String marker) {
        String text = value == null ? "" : value;
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must not be negative");
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String effectiveMarker = marker == null ? TRUNCATION_MARKER : marker;
        if (maxChars <= effectiveMarker.length()) {
            return effectiveMarker.substring(0, maxChars);
        }
        int retained = maxChars - effectiveMarker.length();
        int headLength = (retained + 1) / 2;
        int tailLength = retained - headLength;
        return text.substring(0, headLength)
                + effectiveMarker
                + text.substring(text.length() - tailLength);
    }
}
