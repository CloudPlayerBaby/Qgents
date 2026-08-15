package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * Memory 与来源消息的多对多关系实体，对应表 memory_message_sources（契约 §9）。
 * <p>
 * 复合主键 (memory_id, message_id)，由自定义 SQL 维护。
 */
@Data
@TableName("memory_message_sources")
public class MemoryMessageSourceEntity {

    /**
     * Memory ID（UUIDv7，BINARY(16)）。
     */
    private UUID memoryId;

    /**
     * 作为知识依据的消息 ID（UUIDv7，BINARY(16)）。
     */
    private UUID messageId;
}
