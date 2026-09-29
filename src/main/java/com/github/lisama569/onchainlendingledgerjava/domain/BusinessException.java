package com.github.lisama569.onchainlendingledgerjava.domain;

/**
 * 业务异常：携带可区分的失败原因，供接口层映射为稳定的错误码。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
