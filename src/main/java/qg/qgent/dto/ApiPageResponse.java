package qg.qgent.dto;

import java.util.List;

/**
 * 游标分页列表响应，结构为 {data, page, requestId}。
 */
public record ApiPageResponse<T>(List<T> data, PageMeta page, String requestId) {
}
