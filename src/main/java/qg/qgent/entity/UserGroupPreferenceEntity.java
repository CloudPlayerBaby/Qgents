package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户对需求群的置顶偏好（个人偏好，按「用户 × 群」持久化）。
 * <p>
 * 置顶只影响当前用户自己的群列表展示顺序，不做群公共属性；换设备 / 重新登录后
 * 仍能恢复置顶状态（跨设备同步）。主键为 (user_id, group_id)，重复设置同值幂等。
 * 群被删除或归档时本行可保留（前端按活跃群过滤，不影响展示）。
 */
@Data
@TableName("user_group_preference")
public class UserGroupPreferenceEntity {

    /**
     * 用户 ID（置顶偏好归属者）。
     */
    private UUID userId;

    /**
     * 需求群 ID（置顶目标群）。
     */
    private UUID groupId;

    /**
     * 是否置顶：true 置顶，false 取消置顶；默认 false。
     */
    private Boolean pinned;

    /**
     * 偏好更新时间（UTC）。
     */
    private LocalDateTime updatedAt;
}
