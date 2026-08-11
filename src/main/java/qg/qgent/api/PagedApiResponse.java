package qg.qgent.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import qg.qgent.dto.PageInfo;

import java.util.List;

@Getter
@AllArgsConstructor
public class PagedApiResponse<T> {
    private final List<T> data;
    private final PageInfo page;
    private final String requestId;
}
