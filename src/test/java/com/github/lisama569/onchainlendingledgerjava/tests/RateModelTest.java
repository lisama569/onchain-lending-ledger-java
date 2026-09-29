package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.model.RateModel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 利率模型单测：验证分段线性曲线单调性与关键锚点，日志打印输入/输出与判定依据。
 */
class RateModelTest {

    private static final Logger log = LoggerFactory.getLogger(RateModelTest.class);
    private final RateModel model = new RateModel();

    private BigDecimal pct(String decimal) {
        return new BigDecimal(decimal);
    }

    @Test
    void borrowRateFollowsPiecewiseCurve() {
        TestSupport.section("利率模型-分段曲线锚点");
        check("0% 利用率", "0", "0.02");
        check("80% 利用率(kink)", "0.8", "0.10");
        check("100% 利用率", "1", "0.22");
        check("90% 利用率", "0.9", "0.16");
    }

    @Test
    void rateIsMonotonicWithUtilization() {
        TestSupport.section("利率模型-随利用率单调上升");
        BigDecimal prev = BigDecimal.ZERO;
        for (int i = 1; i <= 20; i++) {
            BigDecimal u = BigDecimal.valueOf(i * 5, 2);
            BigDecimal r = model.borrowRatePerYear(u);
            log.info("输入: utilization={} -> 年化借款利率={} (按秒={})",
                    u.toPlainString(), r.setScale(4, RoundingMode.HALF_UP),
                    model.borrowRatePerSecond(u).toPlainString());
            assertTrue(r.compareTo(prev) >= 0, "利率应随利用率单调上升");
            prev = r;
        }
    }

    @Test
    void supplyRateEqualsBorrowRateTimesUtilizationTimesRetention() {
        TestSupport.section("利率模型-存款利率传导");
        BigDecimal u = pct("0.8");
        MathContext mc = RateModel.MC;
        BigDecimal expected = model.borrowRatePerSecond(u).multiply(u, mc)
                .multiply(BigDecimal.ONE.subtract(model.reserveFactor()), mc);
        BigDecimal actual = model.supplyRatePerSecond(u);
        log.info("输入: utilization=0.8, 判定: supplyRate=borrowRate*u*(1-reserveFactor), 期望={}, 实际={}",
                expected.toPlainString(), actual.toPlainString());
        assertEquals(0, expected.compareTo(actual), "存款利率公式不一致");
    }

    private void check(String label, String u, String expectedYear) {
        BigDecimal r = model.borrowRatePerYear(new BigDecimal(u));
        log.info("输入: {} (utilization={}) -> 年化借款利率={}, 期望锚点={}, 判定={}",
                label, u, r.toPlainString(), expectedYear,
                r.compareTo(new BigDecimal(expectedYear)) == 0 ? "通过" : "不符");
        assertEquals(0, r.compareTo(new BigDecimal(expectedYear)), label + " 利率锚点不符");
    }
}
