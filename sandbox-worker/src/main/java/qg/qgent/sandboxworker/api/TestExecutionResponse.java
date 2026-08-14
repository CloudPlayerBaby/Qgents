package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** 一次受控测试运行的汇总。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class TestExecutionResponse {
    private UUID executionId;
    private String status;
    private String resolvedHeadCommit;
    private List<TestExecutionItemResponse> results;
}
