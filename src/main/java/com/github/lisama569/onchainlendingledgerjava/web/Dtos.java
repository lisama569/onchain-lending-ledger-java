package com.github.lisama569.onchainlendingledgerjava.web;

import java.math.BigDecimal;

/**
 * REST 请求/响应 DTO。
 */
public final class Dtos {
    private Dtos() {
    }

    public record AmountRequest(String requestId, String userId, BigDecimal amount) {
    }

    public record LiquidateRequest(String requestId, String liquidatorId, String borrowerId,
                                   BigDecimal repayAmount) {
    }

    public record PriceRequest(long seq, BigDecimal price, Long sourceTimeMillis) {
    }
}
