package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.dto.GroupLatestMessageRow;
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
     * 可选限定单个群。关键字匹配 content JSON 的 {@code $.text}，按 sequence 倒序。
     *
     * @param projectId 项目 ID
     * @param groupId   可选需求群 ID；为空检索项目全部群
     * @param q         关键字，不能为空
     * @param limit     返回条数上限
     * @return 匹配的消息实体列表，新的在前
     */
    @Select({ "<script>",
            "SELECT m.id, m.requirement_group_id, m.sequence_no, m.author_user_id, m.agent_id, ",
            "m.client_message_id, m.message_type, m.content, m.mentions, m.reply_to_message_id, m.created_at ",
            "FROM messages m ",
            "WHERE m.requirement_group_id IN ",
            "    (SELECT id FROM requirement_groups WHERE project_id = #{projectId}) ",
            "<if test='groupId != null'>AND m.requirement_group_id = #{groupId}</if> ",
            "AND JSON_UNQUOTE(JSON_EXTRACT(m.content, '$.text')) LIKE CONCAT('%', #{q}, '%') ",
            "ORDER BY m.sequence_no DESC LIMIT #{limit}",
            "</script>" })
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
    List<MessageEntity> searchByQuery(@Param("projectId") UUID projectId, @Param("groupId") UUID groupId,
            @Param("q") String q, @Param("limit") int limit);
}
