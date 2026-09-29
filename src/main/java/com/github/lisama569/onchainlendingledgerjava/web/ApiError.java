package com.github.lisama569.onchainlendingledgerjava.web;

/**
 * 统一错误响应：稳定可区分的业务码 + 可读原因。
 */
public record ApiError(boolean success, String requestId, String errorCode, String message) {
}
