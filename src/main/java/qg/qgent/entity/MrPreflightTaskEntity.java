package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/** 分支级预检覆盖的 Task 关联事实。 */
@Data
@TableName("mr_preflight_tasks")
public class MrPreflightTaskEntity {
    private UUID preflightId;
    private UUID taskId;
    private String role;
}
