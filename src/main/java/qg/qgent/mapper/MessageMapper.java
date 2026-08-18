package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.dto.GroupLatestMessageRow;
import qg.qgent.dto.GroupUnreadRow;
import qg.qgent.entity.MessageEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/**
 * 群消息数据访问。
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * 计算群内下一个单调递增消息序号。
     * <p>
     * 调用方必须在事务内先持有群行锁（requirement_groups FOR UPDATE），避免并发重号。
     *
     * @param groupId 需求群 ID
     * @return 下一个序号（当前最大值 + 1，空群为 1）
     */
    @Select("select coalesce(max(sequence_no), 0) + 1 from messages where requirement_group_id=#{groupId}")
    Long nextSequence(UUID groupId);

    /**
     * 批量查询项目下每个群的最新一条消息摘要（群列表 latestMessage 用，避免逐群 N+1）。
     * <p>
     * senderName 通过 LEFT JOIN users/agents 取 {@code COALESCE(display_name, name)}，
     * SYSTEM 消息两者为空则 senderName 为 null；text 取 content JSON 的 {@code $.text}，
     * CODE/IMAGE/FILE/SYSTEM 等类型取不到时为 null。
     *
     * @param projectId 项目 ID
     * @return 每群最新一条消息的摘要行；无消息的群不在结果中
     */
    @Select("SELECT m.requirement_group_id, "
            + "COALESCE(u.display_name, a.name) AS sender_name, "
            + "JSON_UNQUOTE(JSON_EXTRACT(m.content, '$.text')) AS text, "
            + "m.message_type, m.created_at "
            + "FROM messages m "
            + "LEFT JOIN users u ON u.id = m.author_user_id "
            + "LEFT JOIN agents a ON a.id = m.agent_id "
            + "INNER JOIN (SELECT requirement_group_id, MAX(sequence_no) AS max_seq "
            + "            FROM messages "
            + "            WHERE requirement_group_id IN "
            + "                (SELECT id FROM requirement_groups WHERE project_id = #{projectId}) "
            + "            GROUP BY requirement_group_id) t "
            + "ON t.requirement_group_id = m.requirement_group_id AND t.max_seq = m.sequence_no")
    @Results({
            @Result(column = "requirement_group_id", property = "requirementGroupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "sender_name", property = "senderName"),
            @Result(column = "text", property = "text"),
            @Result(column = "message_type", property = "messageType"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<GroupLatestMessageRow> selectLatestByProject(@Param("projectId") UUID projectId);

    /**
     * 按关键字检索项目内群消息的文本内容（点6 上下文检索）。
     * <p>
     * 仅检索当前项目下的消息（通过 requirement_groups 归属限定，防跨项目泄露）；
     * 仅检索调用方已完成访问校验的可见群。关键字匹配 content JSON 的 {@code $.text}，按 sequence 倒序。
     *
     * @param projectId 项目 ID
     * @param groupIds  当前用户可见的群 ID，不能为空列表
     * @param q         关键字，不能为空
     * @param limit     返回条数上限
     * @return 匹配的消息实体列表，新的在前
     */
    @Select({"<script>",
            "SELECT m.id, m.requirement_group_id, m.sequence_no, m.author_user_id, m.agent_id, ",
            "m.client_message_id, m.message_type, m.content, m.mentions, m.reply_to_message_id, m.created_at ",
            "FROM messages m ",
            "INNER JOIN requirement_groups rg ON rg.id = m.requirement_group_id ",
            "WHERE rg.project_id = #{projectId} ",
            "AND m.requirement_group_id IN ",
            "(<foreach collection='groupIds' item='groupId' separator=','>#{groupId}</foreach>) ",
            "AND JSON_UNQUOTE(JSON_EXTRACT(m.content, '$.text')) LIKE CONCAT('%', #{q}, '%') ",
            "ORDER BY m.sequence_no DESC LIMIT #{limit}",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "requirement_group_id", property = "requirementGroupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "author_user_id", property = "authorUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "agent_id", property = "agentId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reply_to_message_id", property = "replyToMessageId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "message_type", property = "messageType"),
            @Result(column = "content", property = "content")
    })
    List<MessageEntity> searchByQuery(@Param("projectId") UUID projectId, @Param("groupIds") List<UUID> groupIds,
                                      @Param("q") String q, @Param("limit") int limit);

    /**
     * 批量计算某用户在某项目各群的未读消息数（群聊未读状态后端权威化）。
     * <p>
     * 未读口径：该群 {@code sequence_no} 大于用户已读游标（无游标视为 0）且**非本人发送**的消息数，
     * 与 {@code group_read_state} 表配合。仅返回未读数 &gt; 0 的群，Java 侧对无记录群补 0。
     *
     * @param projectId 项目 ID（通过 requirement_groups 归属限定，防跨项目泄露）
     * @param userId    当前用户 ID（排除本人消息）
     * @return 群 ID → 未读数 的映射，未读数 &gt; 0 的群
     */
    @Select({"<script>",
            "SELECT m.requirement_group_id, COUNT(*) AS unread ",
            "FROM messages m ",
            "WHERE m.requirement_group_id IN ",
            "    (SELECT id FROM requirement_groups WHERE project_id = #{projectId}) ",
            "AND m.sequence_no &gt; COALESCE(",
            "    (SELECT last_read_sequence_no FROM group_read_state ",
            "     WHERE user_id = #{userId} AND group_id = m.requirement_group_id), 0) ",
            "AND m.author_user_id &lt;&gt; #{userId} ",
            "GROUP BY m.requirement_group_id",
            "</script>"})
    @Results({
            @Result(column = "requirement_group_id", property = "groupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "unread", property = "unread")
    })
    List<GroupUnreadRow> countUnreadByProject(@Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
