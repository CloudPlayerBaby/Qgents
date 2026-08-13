package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已脱敏执行日志条目，用于游标续读。
 * node 为产生日志的执行节点名，单节点运行为空；sequence 为游标字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEntryResponse {
    private String id;
    private Long sequence;
    /** 产生日志的节点名，可为空。 */
    private String node;
    private String content;
    private String timestamp;
}
