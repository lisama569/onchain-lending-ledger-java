package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.service.InterestRateModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 利率模型单测：验证利用率为 0 / kink 点 / 超高利用率下利率合理变化且单调。
 */
class InterestRateModelTest {

    private static final Logger log = LoggerFactory.getLogger(InterestRateModelTest.class);
    private final InterestRateModel model = new InterestRateModel();

    @Test
    @DisplayName("利用率为0时借款利率为0")
    void zeroUtilization() {
        BigDecimal rate = model.borrowRatePerYear(BigDecimal.ZERO);
        log.info("[输入] utilization=0 -> [判定] borrowRate={}", rate);
        assertEquals(0, rate.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("kink点处利率=slope1*0.8=4%，存款利率=3.2%")
    void atKink() {
        BigDecimal u = new BigDecimal("0.80");
        BigDecimal borrow = model.borrowRatePerYear(u);
        BigDecimal supply = model.supplyRatePerYear(u, borrow);
        log.info("[输入] utilization={} -> [状态变化] borrowRate={}, supplyRate={}", u, borrow, supply);
        assertEquals(0, new BigDecimal("0.040000").compareTo(borrow));
        assertEquals(0, new BigDecimal("0.032000").compareTo(supply));
    }

    @Test
    @DisplayName("利用率超过kink后利率显著抬升且整体单调")
    void aboveKinkRisesSharplyAndMonotonic() {
        BigDecimal prev = BigDecimal.ZERO;
        for (String s : new String[]{"0.0", "0.2", "0.5", "0.8", "0.9", "0.95", "1.0"}) {
            BigDecimal u = new BigDecimal(s);
            BigDecimal r = model.borrowRatePerYear(u);
            log.info("[输入] utilization={} -> [判定依据] borrowRate={}", u, r);
            assertTrue(r.compareTo(prev) >= 0, "利率应随利用率单调不减");
            prev = r;
        }
        BigDecimal at90 = model.borrowRatePerYear(new BigDecimal("0.90"));
        BigDecimal atKink = model.borrowRatePerYear(new BigDecimal("0.80"));
        // kink 之上斜率 0.75，0.9 比 0.8 多 0.1 利用率 => 多 7.5%
        assertEquals(0, at90.compareTo(new BigDecimal("0.115000")));
        assertTrue(at90.compareTo(atKink) > 0);
    }
}
