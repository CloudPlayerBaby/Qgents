package qg.qgent.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 游标分页列表响应，与契约 {@code {"data":[], "page":{...}, "requestId":"..."}} 对齐。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    /** 当前页数据。 */
    private List<T> data;

    /** 分页信息。 */
    private Page page;

    /** 请求 ID。 */
    private String requestId;

    public static <T> PagedResponse<T> of(List<T> data, Page page, String requestId) {
        return new PagedResponse<>(data, page, requestId);
    }
}
