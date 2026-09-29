package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 资金池状态（单一 CASH 池）。
 * <ul>
 *   <li>cash：池内当前可用现金；</li>
 *   <li>totalSupplyScaled / supplyIndex：存款侧指数化份额，实际存款 = scaled * index；</li>
 *   <li>totalBorrowScaled / borrowIndex：借款侧指数化份额，实际借款 = scaled * index；</li>
 *   <li>指数 1e18 起步，随利息单调上升，实现惰性计息：不逐用户记账，全局一次计息；</li>
 *   <li>totalReserves：协议储备金（利息的 reserveFactor 部分），仍留在池内现金中。</li>
 * </ul>
 * 守恒恒等式：cash + totalBorrow = totalSupply + totalReserves。
 */
public record PoolState(
        String assetId,
        BigDecimal cash,
        BigDecimal totalSupplyScaled,
        BigDecimal totalBorrowScaled,
        BigDecimal supplyIndex,
        BigDecimal borrowIndex,
        long lastAccrualTimeMillis,
        BigDecimal totalReserves
) {

    public static final BigDecimal INDEX_ONE = new BigDecimal("1000000000000000000"); // 1e18
    public static final int SCALE = 18;
    public static final BigDecimal DUST = new BigDecimal("1E-8"); // 标准单位舍入容差

    public static PoolState initial(String assetId, long nowMillis) {
        return new PoolState(assetId, money(0), money(0), money(0),
                INDEX_ONE, INDEX_ONE, nowMillis, money(0));
    }

    public static BigDecimal money(long v) {
        return BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(String v) {
        return new BigDecimal(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** scaled = 金额 / 指数（*1e18 归一化），HALF_UP 保留高精度。 */
    public static BigDecimal toScaled(BigDecimal amount, BigDecimal index) {
        return amount.multiply(INDEX_ONE).divide(index, 40, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal fromScaled(BigDecimal scaled, BigDecimal index) {
        return scaled.multiply(index).divide(INDEX_ONE, 40, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal totalSupply() {
        return fromScaled(totalSupplyScaled, supplyIndex);
    }

    public BigDecimal totalBorrow() {
        return fromScaled(totalBorrowScaled, borrowIndex);
    }

    /** 利用率 = totalBorrow / (cash + totalBorrow)；分母为 0 时返回 0。 */
    public BigDecimal utilization() {
        BigDecimal tb = totalBorrow();
        BigDecimal denom = cash.add(tb);
        if (denom.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return tb.divide(denom, 18, RoundingMode.HALF_UP);
    }

    /**
     * 惰性计息到 nowMillis：
     * 新增借款利息 = totalBorrow * borrowRate * dt；
     * 新增储备     = 利息 * reserveFactor；
     * 新增存款利息 = 利息 - 储备（由全体存款人按 supplyIndex 分享）；
     * index 增长与实际金额严格对应，且 cash 不变（利息尚未偿还）。
     */
    public PoolState accrue(long nowMillis, java.util.function.Function<BigDecimal, BigDecimal[]> ratesFor) {
        long dtMillis = nowMillis - lastAccrualTimeMillis;
        if (dtMillis <= 0 || totalBorrowScaled.signum() == 0) {
            if (dtMillis < 0) {
                return this;
            }
            return new PoolState(assetId, cash, totalSupplyScaled, totalBorrowScaled,
                    supplyIndex, borrowIndex, nowMillis, totalReserves);
        }
        BigDecimal dtSecs = BigDecimal.valueOf(dtMillis).divide(BigDecimal.valueOf(1000L), 18, RoundingMode.HALF_UP);
        BigDecimal[] rates = ratesFor.apply(utilization());
        BigDecimal borrowRatePerSec = rates[0];
        BigDecimal supplyRatePerSec = rates[1];

        BigDecimal oldBorrow = totalBorrow();
        BigDecimal oldSupply = totalSupply();

        BigDecimal newBorrow = oldBorrow.multiply(BigDecimal.ONE.add(borrowRatePerSec.multiply(dtSecs)))
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal newSupply = oldSupply.multiply(BigDecimal.ONE.add(supplyRatePerSec.multiply(dtSecs)))
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal borrowInterest = newBorrow.subtract(oldBorrow);
        BigDecimal supplyInterest = newSupply.subtract(oldSupply);
        BigDecimal newReserves = totalReserves.add(borrowInterest.subtract(supplyInterest));

        BigDecimal newBorrowIndex = newBorrow.multiply(INDEX_ONE)
                .divide(totalBorrowScaled, 40, RoundingMode.HALF_UP);
        BigDecimal newSupplyIndex = totalSupplyScaled.signum() == 0
                ? supplyIndex
                : newSupply.multiply(INDEX_ONE).divide(totalSupplyScaled, 40, RoundingMode.HALF_UP);

        return new PoolState(assetId, cash, totalSupplyScaled, totalBorrowScaled,
                newSupplyIndex, newBorrowIndex, nowMillis, newReserves);
    }

    public PoolState withCash(BigDecimal newCash) {
        return new PoolState(assetId, newCash, totalSupplyScaled, totalBorrowScaled,
                supplyIndex, borrowIndex, lastAccrualTimeMillis, totalReserves);
    }

    public PoolState withScaledTotals(BigDecimal supplyScaled, BigDecimal borrowScaled) {
        return new PoolState(assetId, cash, supplyScaled, borrowScaled,
                supplyIndex, borrowIndex, lastAccrualTimeMillis, totalReserves);
    }

    public PoolState withReserves(BigDecimal reserves) {
        return new PoolState(assetId, cash, totalSupplyScaled, totalBorrowScaled,
                supplyIndex, borrowIndex, lastAccrualTimeMillis, reserves);
    }
}
