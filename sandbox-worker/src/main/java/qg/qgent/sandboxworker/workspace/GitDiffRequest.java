package qg.qgent.sandboxworker.workspace;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 生成当前完整工作树 Diff 的请求；暂不接受任意 Git 参数。
 */
@Data
@Schema(description = "生成完整工作树 Diff；不接受任意 Git 参数")
public class GitDiffRequest {
}
