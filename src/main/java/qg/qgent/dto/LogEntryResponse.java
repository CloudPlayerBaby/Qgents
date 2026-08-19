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
    /**
     * 日志来源类型：EXECUTION、SYSTEM 或 TERMINAL。
     */
    private String entryType;
    /**
     * 产生日志的节点名，可为空。
     */
    private String node;
    private String content;
    private String timestamp;

    /** 兼容旧的五字段构造调用；历史日志默认为真实执行日志。 */
    public LogEntryResponse(String id, Long sequence, String node, String content, String timestamp) {
        this(id, sequence, "EXECUTION", node, content, timestamp);
    }
}
