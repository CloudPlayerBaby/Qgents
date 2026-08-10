package qg.qgent.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * api 相关 response
 * ApiResponse
 * @param data
 * @param requestId
 */
@Getter
@AllArgsConstructor
public class ApiResponse<T> {
    private final T data;
    private final String requestId;

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}
