package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 游标分页元数据。
 * nextCursor 为空且 hasMore 为 false 表示已到末页。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageMeta {
    /**
     * 下一页游标，末页为空。
     */
    private String nextCursor;
    /**
     * 是否还有更多数据。
     */
    private Boolean hasMore;
}
