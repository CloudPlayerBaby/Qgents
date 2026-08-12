package qg.qgent.sandboxworker.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 tool_execution_logs 表的一条执行日志。
 */
@Data
@TableName("tool_execution_logs")
public class ToolExecutionLogEntity {
    private String executionId;
    private Long sequenceNo;
    private String stream;
    private String content;
    private LocalDateTime createdAt;
}
