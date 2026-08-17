package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.GroupReadStateEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 群成员已读游标数据访问（表 group_read_state）。
 */
@Mapper
public interface GroupReadStateMapper extends BaseMapper<GroupReadStateEntity> {

    /**
     * 推进某用户在某群的已读游标，只前进不后退：目标游标小于当前值时忽略。
     * <p>
     * 采用 MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE} 并用 {@code GREATEST} 保证单调不减，
     * 天然满足幂等（重复调用无副作用）。调用方需已校验项目/群归属。
     *
     * @param userId 用户 ID
     * @param groupId 需求群 ID
     * @param seq 目标已读游标（群内消息序号）
     */
    @Insert("INSERT INTO group_read_state (user_id, group_id, last_read_sequence_no) "
            + "VALUES (#{userId}, #{groupId}, #{seq}) "
            + "ON DUPLICATE KEY UPDATE last_read_sequence_no = "
            + "GREATEST(COALESCE(last_read_sequence_no, 0), VALUES(last_read_sequence_no))")
    void upsertSequence(@Param("userId") UUID userId, @Param("groupId") UUID groupId, @Param("seq") Long seq);

    /**
     * 批量取某用户在一组群内的已读游标，返回 groupId → last_read_sequence_no（无记录不入结果）。
     *
     * @param userId 用户 ID
     * @param groupIds 群 ID 列表
     * @return 群 ID → 已读游标 的映射
     */
    @Select({"<script>",
            "SELECT group_id, last_read_sequence_no FROM group_read_state WHERE user_id = #{userId} ",
            "AND group_id IN "
            + "(<foreach collection='groupIds' item='gid' separator=','>#{gid}</foreach>)",
            "</script>"})
    @Results({
            @Result(column = "group_id", property = "groupId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "last_read_sequence_no", property = "lastReadSequenceNo")
    })
    List<GroupReadStateEntity> selectByUserAndGroupIds(@Param("userId") UUID userId,
                                                       @Param("groupIds") List<UUID> groupIds);
}