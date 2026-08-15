package qg.qgent.api;

/**
 * api 相关 response
 * ApiResponse
 *
 * @param data
 * @param requestId
 */
public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}
