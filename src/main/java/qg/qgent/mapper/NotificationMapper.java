package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.NotificationEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 通知中心数据访问（A 联调约定 §1）。
 * <p>
 * 通知按用户维度持久化；所有查询与已读更新均以 {@code recipient_user_id} 限定当前用户，
 * 避免跨用户读取或误改他人通知。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    /**
     * 查询指定用户的通知列表，按产生时间倒序（一次性返回全量，A 约定不分页）。
     *
     * @param recipientUserId 接收通知的用户 ID
     * @return 该用户的全部通知，新的在前
     */
    @Select("SELECT * FROM notifications WHERE recipient_user_id = #{recipientUserId} ORDER BY created_at DESC")
    List<NotificationEntity> listByRecipient(@Param("recipientUserId") UUID recipientUserId);

    /**
     * 将属于指定用户的一条通知标记为已读；不属于该用户时返回 0（幂等）。
     *
     * @param id       通知 ID
     * @param userId   接收通知的用户 ID
     * @param readTime 已读时间（UTC）
     * @return 受影响行数
     */
    @Update("UPDATE notifications SET is_read = 1, read_at = #{readTime} "
            + "WHERE id = #{id} AND recipient_user_id = #{userId}")
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("readTime") LocalDateTime readTime);

    /**
     * 将指定用户的全部未读通知标记为已读（幂等）。
     *
     * @param userId   接收通知的用户 ID
     * @param readTime 已读时间（UTC）
     * @return 受影响行数
     */
    @Update("UPDATE notifications SET is_read = 1, read_at = #{readTime} "
            + "WHERE recipient_user_id = #{userId} AND is_read = 0")
    int markAllRead(@Param("userId") UUID userId, @Param("readTime") LocalDateTime readTime);
}
