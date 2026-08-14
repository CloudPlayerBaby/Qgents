package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.UUID;

/** 请求 Worker 在受控 Git Store 中解析引用。 */
@Data
public class WorkerGitResolveRequest {
    private UUID repositoryId;
    private String ref;
}
