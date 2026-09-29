package com.github.lisama569.onchainlendingledgerjava.model;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 利率模型：分段线性利用率利率曲线，年化利率口径，按秒线性计息（多次计息等价于按指数复利）。
 * <pre>
 * 利用率 u        = 总借款 / (池内现金 + 总借款)，空池时 u=0
 * u <= kink(0.80): borrowRate = baseRate(2%) + u * slope1(10%)     => 2% ~ 10%
 * u >  kink(0.80): borrowRate = 10% + (u-kink) * slope2(60%)       => 10% ~ 22%(u=1)
 * supplyRate     = borrowRate * u * (1 - reserveFactor=0.10)
 * </pre>
 * 资金利用率越高，借款利率越陡（slope2），引导存款与抑制借款，符合"利率随利用率合理变化"。
 */
@Component
public final class RateModel {

    public static final BigDecimal KINK = new BigDecimal("0.80");
    public static final BigDecimal BASE_RATE = new BigDecimal("0.02");
    public static final BigDecimal SLOPE_1 = new BigDecimal("0.10");
    public static final BigDecimal SLOPE_2 = new BigDecimal("0.60");
    public static final BigDecimal RESERVE_FACTOR = new BigDecimal("0.10");
    public static final BigDecimal SECONDS_PER_YEAR = new BigDecimal("31536000");

    public static final MathContext MC = new MathContext(40, RoundingMode.HALF_UP);

    public BigDecimal borrowRatePerSecond(BigDecimal utilization) {
        return borrowRatePerYear(utilization).divide(SECONDS_PER_YEAR, MC);
    }

    public BigDecimal borrowRatePerYear(BigDecimal utilization) {
        BigDecimal u = utilization == null ? BigDecimal.ZERO : utilization;
        if (u.signum() < 0) {
            u = BigDecimal.ZERO;
        }
        if (u.compareTo(BigDecimal.ONE) > 0) {
            u = BigDecimal.ONE;
        }
        if (u.compareTo(KINK) <= 0) {
            return BASE_RATE.add(u.multiply(SLOPE_1));
        }
        BigDecimal atKink = BASE_RATE.add(KINK.multiply(SLOPE_1));
        return atKink.add(u.subtract(KINK).multiply(SLOPE_2));
    }

    public BigDecimal supplyRatePerSecond(BigDecimal utilization) {
        BigDecimal br = borrowRatePerSecond(utilization);
        BigDecimal u = utilization == null ? BigDecimal.ZERO : utilization;
        return br.multiply(u, MC).multiply(BigDecimal.ONE.subtract(RESERVE_FACTOR), MC);
    }

    public BigDecimal reserveFactor() {
        return RESERVE_FACTOR;
    }
}
