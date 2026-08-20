package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TaskRun 基础设施失败的不可变内部诊断记录。
 *
 * <p>该表不属于项目成员可见的执行产物或公开 API。失败详情必须已脱敏、限长，且不得保存
 * 原始命令、stdout/stderr、环境变量、宿主路径、令牌或异常堆栈。</p>
 */
@Data
@TableName("task_run_failure_diagnostics")
public class TaskRunFailureDiagnosticEntity {
    /** 诊断记录 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目 ID，用于持久化归属校验与运维筛选。 */
    private UUID projectId;
    /** 所属任务 ID。 */
    private UUID taskId;
    /** 每个失败运行只允许一条不可变诊断。 */
    private UUID taskRunId;
    /** 产生失败的步骤 ID。 */
    private UUID taskStepId;
    /** 编排相位：PLAN/CODING/TESTING/REVIEWING。 */
    private String phase;
    /** 内部失败来源，例如 AGENT_EXECUTION 或 ORCHESTRATOR_STARTUP。 */
    private String source;
    /** 原始归一化内部失败码，未知码也保留在受限表中。 */
    private String failureCode;
    /** 与客户端一致的稳定公开失败码。 */
    private String publicFailureCode;
    /** 异常简单类型名；不保存异常堆栈。 */
    private String exceptionType;
    /** 已脱敏、限长的失败上下文。 */
    private String failureDetail;
    /** 脱敏详情的 SHA-256 指纹，方便聚合同类故障。 */
    private String detailFingerprint;
    /** 失败发生时间（UTC）。 */
    private LocalDateTime occurredAt;
    /** 记录创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
