package qg.qgent.api;

import lombok.AllArgsConstructor;
import qg.qgent.dto.PageInfo;

import java.util.List;

@AllArgsConstructor
public record PagedApiResponse<T>(List<T> data, PageInfo page, String requestId) {
}
