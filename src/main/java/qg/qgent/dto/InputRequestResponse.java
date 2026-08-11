package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 人机输入/审批请求响应。
 * 最小响应包含 id、taskRunId、kind、status、prompt、可选 options 与 createdAt。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InputRequestResponse {
    private String id;
    private String taskRunId;
    private String kind;
    private String status;
    private String prompt;
    private List<Object> options;
    private Map<String, Object> answer;
    private String reason;
    private String createdAt;
    private String resolvedAt;
}
