package qg.qgent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * Memory 与来源消息关系数据访问（复合主键，使用自定义 SQL）。
 */
@Mapper
public interface MemoryMessageSourceMapper {

    /**
     * 新增一条 Memory 来源消息关联。
     *
     * @param memoryId  Memory ID
     * @param messageId 消息 ID
     */
    @Insert("INSERT INTO memory_message_sources(memory_id, message_id) VALUES(#{memoryId}, #{messageId})")
    int insertSource(@Param("memoryId") UUID memoryId, @Param("messageId") UUID messageId);

    /**
     * 查询 Memory 关联的来源消息 ID 列表。
     *
     * @param memoryId Memory ID
     * @return 消息 ID 列表
     */
    @Select("SELECT message_id FROM memory_message_sources WHERE memory_id = #{memoryId}")
    List<UUID> selectMessageIds(UUID memoryId);
}
