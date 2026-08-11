package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 群消息游标分页结果载体（Controller 组装为 {@code PagedResponse}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageListResponse {

    /** 当前页消息，新消息在前。 */
    private List<MessageResponse> messages;

    /** 下一页游标；无更多数据为空。 */
    private String nextCursor;

    /** 是否还有更多数据。 */
    private boolean hasMore;
}
