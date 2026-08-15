package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Git 引用解析得到的不可变 commit SHA。
 */
@Data
@AllArgsConstructor
public class GitResolveResponse {
    private String commitSha;
}
