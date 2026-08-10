package qg.qgent.api;

public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}
