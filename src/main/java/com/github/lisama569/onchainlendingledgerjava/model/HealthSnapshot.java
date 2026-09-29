package com.github.lisama569.onchainlendingledgerjava.model;

import java.math.BigDecimal;

/**
 * 仓位健康度判定结果（每一项都可解释，用于日志与 API 返回）。
 * <pre>
 * collateralValue = 抵押数量 * 价格
 * healthFactor    = collateralValue * liquidationThreshold / debtValue
 * liquidatable    = debtValue > 0 且 healthFactor < 1
 * </pre>
 * 债务为 0 时 healthFactor 记为 null（概念上为 +∞，不可清算）。
 */
public record HealthSnapshot(
        String userId,
        BigDecimal collateralAmount,
        BigDecimal price,
        BigDecimal collateralValue,
        BigDecimal debtValue,
        BigDecimal liquidationThreshold,
        BigDecimal healthFactor,
        boolean liquidatable,
        String reason
) {

    public static HealthSnapshot healthyNoDebt(String userId, BigDecimal collateralAmount,
                                               BigDecimal price, BigDecimal collateralValue,
                                               BigDecimal threshold) {
        return new HealthSnapshot(userId, collateralAmount, price, collateralValue,
                BigDecimal.ZERO.setScale(18), threshold, null, false, "无借款债务，健康度为 +∞");
    }
}
