package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.UserGroupPreferenceEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/**
 * 用户 × 群置顶偏好数据访问（表 user_group_preference）。
 */
@Mapper
public interface UserGroupPreferenceMapper extends BaseMapper<UserGroupPreferenceEntity> {

    /**
     * 设置 / 取消某用户对某群的置顶状态。
     * <p>
     * 采用 MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE}，以 (user_id, group_id) 复合主键
     * 幂等覆盖：重复设置相同值无副作用。调用方需已校验项目/群归属。
     *
     * @param userId  用户 ID
     * @param groupId 需求群 ID
     * @param pinned  是否置顶
     */
    @Insert("INSERT INTO user_group_preference (user_id, group_id, pinned) "
            + "VALUES (#{userId}, #{groupId}, #{pinned}) "
            + "ON DUPLICATE KEY UPDATE pinned = VALUES(pinned)")
    void upsertPin(@Param("userId") UUID userId, @Param("groupId") UUID groupId, @Param("pinned") boolean pinned);

    /**
     * 批量取某用户在一组群中的置顶群 ID（仅 pinned = true 者），一次 IN 查询避免 N+1。
     *
     * @param userId   用户 ID
     * @param groupIds 群 ID 列表
     * @return 置顶的群 ID 列表（未置顶群不入结果）
     */
    @Select({"<script>",
            "SELECT group_id FROM user_group_preference WHERE user_id = #{userId} AND pinned = 1 ",
            "AND group_id IN "
            + "(<foreach collection='groupIds' item='gid' separator=','>#{gid}</foreach>)",
            "</script>"})
    @Results({
            @Result(column = "group_id", property = "groupId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<UUID> selectPinnedGroupIds(@Param("userId") UUID userId, @Param("groupIds") List<UUID> groupIds);
}
