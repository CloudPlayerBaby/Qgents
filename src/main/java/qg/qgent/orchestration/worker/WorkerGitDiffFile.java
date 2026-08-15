package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Structured file summary returned alongside a Worker Git patch.
 */
@Data
public class WorkerGitDiffFile {
    private String path;
    private String previousPath;
    private String changeType;
    private int additions;
    private int deletions;
    private boolean binary;
    private List<Map<String, Object>> hunks;
}
