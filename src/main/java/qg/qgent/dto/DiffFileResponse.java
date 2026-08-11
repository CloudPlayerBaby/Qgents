package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Diff 文件摘要，含 hunk 与二进制文件标识。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffFileResponse {
    private String id;
    private Long sequence;
    private String path;
    private String changeType;
    private Integer additions;
    private Integer deletions;
    private Boolean binary;
    private List<Object> hunks;
}
