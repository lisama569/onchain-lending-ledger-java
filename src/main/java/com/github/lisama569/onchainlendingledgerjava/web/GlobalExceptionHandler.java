package com.github.lisama569.onchainlendingledgerjava.web;

import com.github.lisama569.onchainlendingledgerjava.common.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：任何失败都映射为可区分原因码的 OperationResult，HTTP 层不抛裸异常。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public OperationResult<Void> handleBusiness(BusinessException ex) {
        log.warn("业务异常: code={}, msg={}", ex.reasonCode(), ex.getMessage());
        return OperationResult.fail(null, ex.reasonCode(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public OperationResult<Void> handleBadJson(HttpMessageNotReadableException ex) {
        return OperationResult.fail(null, ReasonCode.INVALID_AMOUNT, "请求体无法解析: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public OperationResult<Void> handleOther(Exception ex) {
        log.error("未预期异常", ex);
        return OperationResult.fail(null, ReasonCode.INTERNAL_ERROR, ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }
}
