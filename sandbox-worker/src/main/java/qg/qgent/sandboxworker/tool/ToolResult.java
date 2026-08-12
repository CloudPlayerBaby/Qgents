package qg.qgent.sandboxworker.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 工具处理器返回的结构化结果与日志。 */
@Data
@AllArgsConstructor
public class ToolResult {
    private Integer exitCode;
    private Map<String, Object> result;
    private List<String> standardOutput;
    private List<String> standardError;

    public static ToolResult value(Map<String, Object> result) {
        return new ToolResult(0, result, List.of(), List.of());
    }
}
