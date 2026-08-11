package qg.qgent.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 游标分页信息，作为列表响应中 {@code page} 字段（契约 §2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Page {

    /** 下一页游标；{@code hasMore=false} 时可为 null。 */
    private String nextCursor;

    /** 是否还有更多数据。 */
    private boolean hasMore;
}
