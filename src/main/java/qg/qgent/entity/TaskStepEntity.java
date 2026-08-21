package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/**
 * Planned workflow node with explicit instructions, role, Agent assignment and acceptance criteria.
 */
@Data
@TableName(value = "task_steps", autoResultMap = true)
public class TaskStepEntity {
    /**
     * UUIDv7 step identifier.
     */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * Task whose workflow contains this step.
     */
    private UUID taskId;
    /**
     * Stable order hint; dependencies remain authoritative.
     */
    private Integer sequenceNo;
    /**
     * Short step title.
     */
    private String title;
    /**
     * Exact work instructions for the assigned Agent.
     */
    private String instruction;
    /**
     * Required execution role such as PLANNER/DEVELOPER/TESTER/REVIEWER.
     */
    private String role;
    /**
     * Replaceable Agent identifier; may be null until scheduling.
     */
    private UUID assignedAgentId;
    /**
     * Acceptance criteria for this step.
     */
    private String acceptanceCriteria;
    /**
     * Planner 为该步骤声明的能力标签；用于固定 Agent 选择依据并供看板展示。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> requiredCapabilities;
    /** Planner 为该步骤冻结的 Workspace 相对可写路径；空值仅兼容迁移前历史步骤。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> allowedPaths;
    /**
     * Planner 为该步骤声明的目标文件（Workspace 相对路径），用于运行期判定目标是否已被
     * 前序步骤满足（目标已满足时无新增写入不算失败）；空值关闭该判定。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> targetFiles;
    /** 执行语义：MUTATE/VERIFY/TEST/REVIEW/PLAN。 */
    private String executionMode;
    /**
     * Planner 物化时冻结的按仓库验证命令（仅 TEST 步骤非空）：每条命令含目标仓库的
     * workspacePath（空表示 Workspace 根）与白名单验证命令。供 Test Agent 在运行/恢复
     * 续跑时优先消费，不依赖内存中的 PlanResult（恢复时 planResult 为 null）。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<VerificationCommand> verificationCommands;
    /**
     * State: PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/CANCELLED.
     */
    private String status;
    /**
     * UTC creation time.
     */
    private LocalDateTime createdAt;
    /**
     * UTC last-update time.
     */
    private LocalDateTime updatedAt;

    /**
     * 单条结构化验证命令：目标仓库目录 + 白名单命令向量。
     * 与 {@code PlanResult.VerificationCommand} 同形状，供物化/装配双向转换。
     */
    @Data
    public static class VerificationCommand {
        /** 目标仓库目录（Workspace 相对路径，与 worktree workspacePath 一致）；空表示 Workspace 根。 */
        private String repositoryPath;
        /** 白名单验证命令，如 ["node", "tests/todo.test.js"]。 */
        private List<String> command = new java.util.ArrayList<>();
    }
}
