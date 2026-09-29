package com.github.lisama569.onchainlendingledgerjava.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 分段线性（kinked）利率模型：
 * <pre>
 *   U <= U_opt : borrowRate = baseRate + slope1 * U
 *   U >  U_opt : borrowRate = baseRate + slope1*U_opt + slope2 * (U - U_opt)
 *   supplyRate = borrowRate * U                 （协议不留储备，利息全给出借方）
 * </pre>
 * 利率均为年化、小数表示（0.05 = 5%）。利用率越高利率越高，高过最优利用率后斜率显著变陡。
 */
@Component
public class InterestRateModel {

    public static final int RATE_SCALE = 18;

    public static final BigDecimal OPTIMAL_UTILIZATION = new BigDecimal("0.80");
    public static final BigDecimal BASE_RATE = new BigDecimal("0.00");
    public static final BigDecimal SLOPE_BELOW_KINK = new BigDecimal("0.05");
    public static final BigDecimal SLOPE_ABOVE_KINK = new BigDecimal("0.75");

    /** 根据利用率计算年化借款利率。 */
    public BigDecimal borrowRatePerYear(BigDecimal utilization) {
        BigDecimal u = clamp(utilization);
        if (u.compareTo(OPTIMAL_UTILIZATION) <= 0) {
            return BASE_RATE.add(SLOPE_BELOW_KINK.multiply(u)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal below = SLOPE_BELOW_KINK.multiply(OPTIMAL_UTILIZATION);
        BigDecimal above = SLOPE_ABOVE_KINK.multiply(u.subtract(OPTIMAL_UTILIZATION));
        return BASE_RATE.add(below).add(above).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 当前池参数下的年化存款利率（无储备，借款利息全给出借方）。 */
    public BigDecimal supplyRatePerYear(BigDecimal utilization, BigDecimal borrowRate) {
        return borrowRate.multiply(clamp(utilization)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal u) {
        if (u == null || u.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return u.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : u;
    }
}
