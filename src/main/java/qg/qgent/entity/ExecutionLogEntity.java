package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 已脱敏的执行日志条目。
 * 内容禁止包含 Token、密码、GitHub 安装令牌、私钥或未脱敏的环境变量；
 * 以任务运行内单调递增的 sequenceNo 作为游标供分页续读。
 */
@Data
@TableName("execution_logs")
public class ExecutionLogEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属任务运行ID。
     */
    private UUID taskRunId;
    /**
     * 运行内单调递增日志序号，用于游标分页。
     */
    private Long sequenceNo;
    /**
     * 产生日志的节点名，单节点运行为空。
     */
    private String node;
    /**
     * 已脱敏的日志内容。
     */
    private String content;
    private LocalDateTime createdAt;
}
