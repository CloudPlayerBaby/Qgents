package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Task 级验收标准，对应表 task_acceptance_criteria。
 * <p>
 * 用于表达整个需求的验收目标，区别于 TaskStep.acceptance_criteria（仅约束单个执行步骤）。
 * status 枚举：PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE；验收状态必须由后端结果或检查资源写入，
 * 前端只能只读展示，不能自行改写。当前由 Planner/编排后续写入，尚无生产者时返回空列表。
 */
@Data
@TableName("task_acceptance_criteria")
public class TaskAcceptanceCriterionEntity {

    /**
     * 验收标准 UUIDv7（BINARY(16)）。
     */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /**
     * 所属任务 ID（UUIDv7，BINARY(16)）。
     */
    private UUID taskId;

    /**
     * 任务内验收标准序号，从 1 开始，(task_id, sequence_no) 唯一。
     */
    private Integer sequenceNo;

    /**
     * 验收标准标题。
     */
    private String title;

    /**
     * 验收标准补充说明，可为空。
     */
    private String description;

    /**
     * 验收状态枚举：PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE。
     */
    private String status;

    /**
     * 创建时间（UTC）。
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间（UTC）。
     */
    private LocalDateTime updatedAt;
}
