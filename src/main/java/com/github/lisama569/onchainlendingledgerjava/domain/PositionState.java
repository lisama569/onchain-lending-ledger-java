package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 用户仓位（scaled 份额以池指数计价，实现惰性计息）：
 * <ul>
 *   <li>scaledSupply：存款份额，实际存款 = scaledSupply * pool.supplyIndex()；</li>
 *   <li>scaledBorrow：借款份额，实际借款 = scaledBorrow * pool.borrowIndex()；</li>
 *   <li>collateral：抵押品 RISK 数量（独立托管，不产生利息）。</li>
 * </ul>
 */
public record PositionState(
        String userId,
        BigDecimal scaledSupply,
        BigDecimal scaledBorrow,
        BigDecimal collateral
) {

    public static PositionState empty(String userId) {
        return new PositionState(userId, PoolState.money(0), PoolState.money(0), PoolState.money(0));
    }

    public PositionState withScaledSupply(BigDecimal v) {
        return new PositionState(userId, v, scaledBorrow, collateral);
    }

    public PositionState withScaledBorrow(BigDecimal v) {
        return new PositionState(userId, scaledSupply, v, collateral);
    }

    public PositionState withCollateral(BigDecimal v) {
        return new PositionState(userId, scaledSupply, scaledBorrow, v);
    }

    public BigDecimal supplyBalance(PoolState pool) {
        return PoolState.fromScaled(scaledSupply, pool.supplyIndex());
    }

    public BigDecimal borrowBalance(PoolState pool) {
        return PoolState.fromScaled(scaledBorrow, pool.borrowIndex());
    }

    public PositionState addScaledSupply(BigDecimal delta) {
        return withScaledSupply(scaledSupply.add(delta).setScale(PoolState.SCALE, RoundingMode.HALF_UP));
    }

    public PositionState addScaledBorrow(BigDecimal delta) {
        return withScaledBorrow(scaledBorrow.add(delta).setScale(PoolState.SCALE, RoundingMode.HALF_UP));
    }

    public PositionState addCollateral(BigDecimal delta) {
        return withCollateral(collateral.add(delta).setScale(PoolState.SCALE, RoundingMode.HALF_UP));
    }
}
