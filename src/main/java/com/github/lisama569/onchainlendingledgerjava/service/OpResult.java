package com.github.lisama569.onchainlendingledgerjava.service;

/**
 * 操作提交结果。replayed=true 表示这是对历史已完成请求的重放，
 * 状态未被二次修改，仅返回原有结论。
 */
public record OpResult(boolean success, boolean replayed, String requestId, String operation,
                       String errorCode, String message) {

    public static OpResult ok(String requestId, String operation) {
        return new OpResult(true, false, requestId, operation, null, "OK");
    }

    public static OpResult replayed(boolean success, String requestId, String operation,
                                    String errorCode, String message) {
        return new OpResult(success, true, requestId, operation, errorCode, message);
    }

    public static OpResult failure(String requestId, String operation, String errorCode, String message) {
        return new OpResult(false, false, requestId, operation, errorCode, message);
    }
}
