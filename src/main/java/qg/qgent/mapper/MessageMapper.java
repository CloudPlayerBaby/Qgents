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
     * 按群内消息序号拉取增量消息，返回严格升序结果。
     * 调用方必须先完成需求群成员权限校验；sequence_no 是服务端分配的可靠游标。
     */
    @Select("SELECT id, requirement_group_id, sequence_no, author_user_id, agent_id, client_message_id, "
            + "message_type, content, mentions, reply_to_message_id, created_at "
            + "FROM messages WHERE requirement_group_id = #{groupId} AND sequence_no > #{afterSequence} "
            + "ORDER BY sequence_no ASC LIMIT #{limit}")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "requirement_group_id", property = "requirementGroupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "author_user_id", property = "authorUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "agent_id", property = "agentId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "reply_to_message_id", property = "replyToMessageId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "message_type", property = "messageType"),
            @Result(column = "content", property = "content"),
            @Result(column = "mentions", property = "mentions")
    })
    List<MessageEntity> selectAfterSequence(@Param("groupId") UUID groupId,
                                             @Param("afterSequence") long afterSequence,
                                             @Param("limit") int limit);

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
     * 批量查询指定群的最新一条消息摘要。调用方必须先完成项目与群成员权限校验。
     *
     * @param groupIds 已确认可见的群 ID
     * @return 每个有消息群的最新消息摘要
     */
    @Select({"<script>",
            "SELECT m.requirement_group_id, ",
            "COALESCE(u.display_name, a.name) AS sender_name, ",
            "JSON_UNQUOTE(JSON_EXTRACT(m.content, '$.text')) AS text, ",
            "m.message_type, m.created_at ",
            "FROM messages m ",
            "LEFT JOIN users u ON u.id = m.author_user_id ",
            "LEFT JOIN agents a ON a.id = m.agent_id ",
            "INNER JOIN (SELECT requirement_group_id, MAX(sequence_no) AS max_seq ",
            "            FROM messages ",
            "            WHERE requirement_group_id IN ",
            "            (<foreach collection='groupIds' item='groupId' separator=','>#{groupId}</foreach>) ",
            "            GROUP BY requirement_group_id) t ",
            "ON t.requirement_group_id = m.requirement_group_id AND t.max_seq = m.sequence_no ",
            "WHERE m.requirement_group_id IN ",
            "(<foreach collection='groupIds' item='groupId' separator=','>#{groupId}</foreach>)",
            "</script>"})
    @Results({
            @Result(column = "requirement_group_id", property = "requirementGroupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "sender_name", property = "senderName"),
            @Result(column = "text", property = "text"),
            @Result(column = "message_type", property = "messageType"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<GroupLatestMessageRow> selectLatestByGroupIds(@Param("groupIds") List<UUID> groupIds);

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
     * 批量计算指定可见群的未读消息数（排除本人消息）。
     * <p>已读游标通过 LEFT JOIN 一次关联，避免对每条消息执行相关子查询。</p>
     *
     * @param groupIds 已确认可见的群 ID
     * @param userId 当前用户 ID
     * @return 群 ID → 未读数，仅返回未读数大于 0 的群
     */
    @Select({"<script>",
            "SELECT m.requirement_group_id, COUNT(*) AS unread ",
            "FROM messages m ",
            "LEFT JOIN group_read_state r ON r.user_id = #{userId} ",
            "AND r.group_id = m.requirement_group_id ",
            "WHERE m.requirement_group_id IN ",
            "(<foreach collection='groupIds' item='groupId' separator=','>#{groupId}</foreach>) ",
            "AND m.sequence_no &gt; COALESCE(r.last_read_sequence_no, 0) ",
            "AND m.author_user_id &lt;&gt; #{userId} ",
            "GROUP BY m.requirement_group_id",
            "</script>"})
    @Results({
            @Result(column = "requirement_group_id", property = "groupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "unread", property = "unread")
    })
    List<GroupUnreadRow> countUnreadByGroupIds(@Param("groupIds") List<UUID> groupIds,
                                               @Param("userId") UUID userId);

    /**
     * 兼容旧调用方的项目级未读统计；新业务代码应优先传入已校验的群 ID 集合。
     */
    @Deprecated
    @Select({"<script>",
            "SELECT m.requirement_group_id, COUNT(*) AS unread ",
            "FROM messages m ",
            "INNER JOIN requirement_groups g ON g.id = m.requirement_group_id ",
            "LEFT JOIN group_read_state r ON r.user_id = #{userId} ",
            "AND r.group_id = m.requirement_group_id ",
            "WHERE g.project_id = #{projectId} ",
            "AND m.sequence_no &gt; COALESCE(r.last_read_sequence_no, 0) ",
            "AND m.author_user_id &lt;&gt; #{userId} ",
            "GROUP BY m.requirement_group_id",
            "</script>"})
    @Results({
            @Result(column = "requirement_group_id", property = "groupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "unread", property = "unread")
    })
    List<GroupUnreadRow> countUnreadByProject(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    /**
     * 统计当前用户在指定可见群中「@ 我」的未读消息数（排除本人消息）。
     * mentions 为 JSON 数组（如 {@code [{"type":"USER","id":"..."}]}），用 JSON_CONTAINS 匹配
     * {@code {"type":"USER","id":当前用户}}；未读 = sequence_no 大于已读游标。
     * <p>
     * 注意：mentions 里存的 id 是 Jackson 序列化的字符串 UUID；而 {@code userId} 参数受全局
     * {@link UuidBinaryTypeHandler} 影响会以 BINARY(16) 绑定，直接 CAST 成乱码，JSON_CONTAINS
     * 永远匹配不上。因此 JSON 匹配必须使用独立传入的字符串参数 {@code userIdText}。
     *
     * @param groupIds   已确认可见的群 ID
     * @param userId     当前用户 ID（BINARY(16) 绑定，用于游标/发送者比较）
     * @param userIdText 当前用户 ID 的字符串形式（JSON 匹配用）
     * @return 群 ID → @ 我的未读消息数
     */
    @Select({"<script>",
            "SELECT m.requirement_group_id, COUNT(*) AS unread ",
            "FROM messages m ",
            "LEFT JOIN group_read_state r ON r.user_id = #{userId} ",
            "AND r.group_id = m.requirement_group_id ",
            "WHERE m.requirement_group_id IN ",
            "(<foreach collection='groupIds' item='groupId' separator=','>#{groupId}</foreach>) ",
            "AND m.sequence_no &gt; COALESCE(r.last_read_sequence_no, 0) ",
            "AND m.author_user_id &lt;&gt; #{userId} ",
            "AND JSON_CONTAINS(m.mentions, JSON_OBJECT('type', 'USER', 'id', #{userIdText})) ",
            "GROUP BY m.requirement_group_id",
            "</script>"})
    @Results({
            @Result(column = "requirement_group_id", property = "groupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "unread", property = "unread")
    })
    List<GroupUnreadRow> countMentionUnreadByGroupIds(@Param("groupIds") List<UUID> groupIds,
                                                      @Param("userId") UUID userId,
                                                      @Param("userIdText") String userIdText);

    /**
     * 兼容旧调用方的项目级「@ 我」未读统计；新业务代码应优先传入已校验的群 ID 集合。
     */
    @Deprecated
    @Select({"<script>",
            "SELECT m.requirement_group_id, COUNT(*) AS unread ",
            "FROM messages m ",
            "INNER JOIN requirement_groups g ON g.id = m.requirement_group_id ",
            "LEFT JOIN group_read_state r ON r.user_id = #{userId} ",
            "AND r.group_id = m.requirement_group_id ",
            "WHERE g.project_id = #{projectId} ",
            "AND m.sequence_no &gt; COALESCE(r.last_read_sequence_no, 0) ",
            "AND m.author_user_id &lt;&gt; #{userId} ",
            "AND JSON_CONTAINS(m.mentions, JSON_OBJECT('type', 'USER', 'id', #{userIdText})) ",
            "GROUP BY m.requirement_group_id",
            "</script>"})
    @Results({
            @Result(column = "requirement_group_id", property = "groupId",
                    typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "unread", property = "unread")
    })
    List<GroupUnreadRow> countMentionUnreadByProject(@Param("projectId") UUID projectId,
                                                     @Param("userId") UUID userId,
                                                     @Param("userIdText") String userIdText);
}
