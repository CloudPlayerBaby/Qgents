package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 门禁检查详情。checkType 枚举：TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestCheckResponse {
    private String id;
    private String type;
    private String status;
    private Integer attemptNo;
    private String testsetId;
    private String commitSha;
    private String source;
    private Map<String, Object> summary;
    private String startedAt;
    private String completedAt;
}
