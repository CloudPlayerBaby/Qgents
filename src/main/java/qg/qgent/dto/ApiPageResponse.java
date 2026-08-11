package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 游标分页列表响应，结构为 {data, page, requestId}。
 */
@Getter
@AllArgsConstructor
public class ApiPageResponse<T> {
    private final List<T> data;
    private final PageMeta page;
    private final String requestId;
}
