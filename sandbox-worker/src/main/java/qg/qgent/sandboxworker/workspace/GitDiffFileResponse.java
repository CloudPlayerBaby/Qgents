package qg.qgent.sandboxworker.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Structured file metadata accompanying an immutable Git patch snapshot.
 * <p>
 * {@code hunks} 为逐行变更结构（header + lines），由 {@link UnifiedDiffHunkParser}
 * 从 unified diff patch 解析；二进制文件或无内容变更的文件返回空列表。
 */
@Data
@AllArgsConstructor
@Schema(description = "Git Diff file summary")
public class GitDiffFileResponse {
    private String path;
    private String previousPath;
    private String changeType;
    private int additions;
    private int deletions;
    private boolean binary;
    private List<Map<String, Object>> hunks;
}
