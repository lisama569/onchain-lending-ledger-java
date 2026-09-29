package com.github.lisama569.onchainlendingledgerjava.common;

/**
 * 业务异常：携带可区分的失败原因码。
 */
public class BusinessException extends RuntimeException {
    private final ReasonCode reasonCode;

    public BusinessException(ReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ReasonCode reasonCode() { return reasonCode; }
}
