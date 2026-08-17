package qg.qgent.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.dto.GroupMemberRow;

import java.util.List;
import java.util.UUID;

/**
 * 需求群显式成员关系数据访问（复合主键，使用自定义 SQL）。
 */
@Mapper
public interface GroupMemberMapper {

    /**
     * 将项目成员加入需求群（幂等：已存在则忽略，复合主键去重）。
     *
     * @param groupId 需求群 ID
     * @param userId  项目成员用户 ID
     * @return 影响行数（0 表示已存在）
     */
    @Insert("INSERT IGNORE INTO group_members(requirement_group_id, user_id) VALUES(#{groupId}, #{userId})")
    int insertMember(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /**
     * 将项目成员移出需求群；不存在时影响行数为 0。
     *
     * @param groupId 需求群 ID
     * @param userId  项目成员用户 ID
     * @return 影响行数（0 表示不在群内）
     */
    @Delete("DELETE FROM group_members WHERE requirement_group_id = #{groupId} AND user_id = #{userId}")
    int deleteMember(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /**
     * 查询需求群内的用户成员 ID 列表（不含 Agent，Agent 走 group_agents）。
     *
     * @param groupId 需求群 ID
     * @return 用户成员 ID 列表
     */
    @Select("SELECT user_id FROM group_members WHERE requirement_group_id = #{groupId}")
    List<UUID> selectUserIds(@Param("groupId") UUID groupId);

    /**
     * 查询需求群内的用户成员（join 用户基础信息，含 email 供成员管理弹窗展示）。
     * 与项目成员列表的 GroupMemberRow 形状一致，额外携带 email。
     *
     * @param groupId 需求群 ID
     * @return 群用户成员视图列表
     */
    @Select("SELECT u.id AS user_id, u.display_name, u.avatar_url, u.email "
            + "FROM group_members gm JOIN users u ON u.id = gm.user_id "
            + "WHERE gm.requirement_group_id = #{groupId}")
    @Results({
            @Result(column = "user_id", property = "userId"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "email", property = "email")
    })
    List<GroupMemberRow> selectMembersWithUsers(@Param("groupId") UUID groupId);

    /**
     * 判断用户是否在需求群内。
     *
     * @param groupId 需求群 ID
     * @param userId  用户 ID
     * @return 是否群成员
     */
    @Select("SELECT COUNT(*) FROM group_members WHERE requirement_group_id = #{groupId} AND user_id = #{userId}")
    int countMember(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /**
     * 需求群内用户成员数（不含 Agent）。
     *
     * @param groupId 需求群 ID
     * @return 群用户成员数
     */
    @Select("SELECT COUNT(*) FROM group_members WHERE requirement_group_id = #{groupId}")
    long countMembers(@Param("groupId") UUID groupId);

    /**
     * 查询用户在项目内可见（已加入）的 REQUIREMENT 需求群 ID 列表（任务中心按群过滤用）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @return 需求群 ID 列表
     */
    @Select("SELECT gm.requirement_group_id FROM group_members gm "
            + "JOIN requirement_groups rg ON rg.id = gm.requirement_group_id "
            + "WHERE gm.user_id = #{userId} AND rg.project_id = #{projectId} "
            + "AND rg.group_type = 'REQUIREMENT'")
    List<UUID> selectGroupIdsByUser(@Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
