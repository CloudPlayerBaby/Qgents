package qg.qgent.api;

import qg.qgent.dto.PageInfo;

import java.util.List;

public record PagedApiResponse<T>(List<T> data, PageInfo page, String requestId) {
}
