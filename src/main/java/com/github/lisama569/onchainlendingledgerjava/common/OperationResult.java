package com.github.lisama569.onchainlendingledgerjava.common;

import java.util.Map;

/**
 * 统一操作结果：成功/失败 + 可区分原因码 + 可解释详情。
 * <p>details 用于打印关键输入、状态变化与判定依据，保证失败可区分、结果可解释。
 */
public record OperationResult<T>(
        boolean success,
        ReasonCode reason,
        String message,
        String requestId,
        T data,
        Map<String, Object> details
) {
    public static <T> OperationResult<T> ok(String requestId, T data, Map<String, Object> details) {
        return new OperationResult<>(true, ReasonCode.OK, "OK", requestId, data, details);
    }

    public static <T> OperationResult<T> fail(String requestId, ReasonCode reason, String message) {
        return new OperationResult<>(false, reason, message, requestId, null,
                Map.of("reasonCode", reason.name(), "message", message));
    }

    public static <T> OperationResult<T> fail(String requestId, ReasonCode reason, String message,
                                              Map<String, Object> details) {
        return new OperationResult<>(false, reason, message, requestId, null, details);
    }
}
