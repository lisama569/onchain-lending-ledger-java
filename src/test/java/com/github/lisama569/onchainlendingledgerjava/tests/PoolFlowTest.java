package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资金池正常/异常链路：
 * 存 -> 抵押 -> 借 -> 计息 -> 还 -> 取，及各类可区分失败原因。
 */
class PoolFlowTest {

    private static final Logger log = LoggerFactory.getLogger(PoolFlowTest.class);

    @TempDir
    Path dir;
    private TestSupport.MutableClock clock;
    private LedgerService ledger;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v).setScale(18, RoundingMode.HALF_UP);
    }

    private PriceTick price(long seq, String price) {
        return new PriceTick(seq, bd(price), clock.millis(), clock.millis());
    }

    private <T> T ok(OperationResult<T> r) {
        assertTrue(r.success(), () -> "期望成功但失败: " + r.reason() + " " + r.message());
        return r.data();
    }

    private ReasonCode fail(OperationResult<?> r) {
        assertFalse(r.success(), "期望失败但成功");
        log.info("失败原因可区分: reasonCode={}, message={}", r.reason(), r.message());
        return r.reason();
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = TestSupport.clock();
        ledger = TestSupport.newLedger(dir, clock);
    }

    @Test
    void fullLifecycleWithInterest() {
        TestSupport.section("正常链路-存/押/借/计息/还/取");
        ok(ledger.submitPrice(price(1, "100")));

        ok(ledger.supply("s1", "alice", bd("1000")));
        log.info("输入: alice 存入 1000 -> 池现金={}", ledger.pool().cash().toPlainString());

        ok(ledger.depositCollateral("c1", "bob", bd("20")));
        // bob 抵押 20 RISK @100 => 价值 2000；阈值 0.8 => 可借上限对应 HF>=1
        ok(ledger.borrow("b1", "bob", bd("800")));
        log.info("输入: bob 抵押20@100, 借款800 -> 利用率={}", ledger.pool().utilization().toPlainString());

        assertEquals(0, bd("0.8").compareTo(ledger.pool().utilization()), "利用率应为 0.8");

        // 经过整整 1 年（365 天），u 一直为 0.8：年化借款利率 10%，存款利率 10%*0.8*0.9=7.2%
        clock.advance(365L * 24 * 3600 * 1000);

        BigDecimal bobDebt = ledger.position("bob").borrowBalance(ledger.pool());
        BigDecimal aliceSupply = ledger.position("alice").supplyBalance(ledger.pool());
        log.info("状态变化(1年后): bob债务={} (期望880), alice存款={} (期望1072), 池储备={}",
                bobDebt.toPlainString(), aliceSupply.toPlainString(),
                ledger.pool().totalReserves().toPlainString());
        assertEquals(0, bd("880").compareTo(bobDebt), "一年后债务应为 880");
        assertEquals(0, bd("1072").compareTo(aliceSupply), "一年后存款应为 1072");

        // bob 全额还款 880（链下注入现金）
        ok(ledger.repay("r1", "bob", bd("880")));
        // alice 取走 1072 本息，池内剩 8 = 储备金
        ok(ledger.withdraw("w1", "alice", bd("1072")));
        log.info("状态变化: 清算后池现金={}, 储备={}, 借款总额={}",
                ledger.pool().cash().toPlainString(),
                ledger.pool().totalReserves().toPlainString(),
                ledger.pool().totalBorrow().toPlainString());
        assertEquals(0, bd("8").compareTo(ledger.pool().cash()), "现金=储备=8，守恒");
        assertEquals(0, bd("8").compareTo(ledger.pool().totalReserves()));
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.pool().totalBorrow()));
    }

    @Test
    void invalidInputsGiveDistinctReasons() {
        TestSupport.section("异常链路-非法输入与余额不足");
        assertEquals(ReasonCode.INVALID_AMOUNT, fail(ledger.supply("", "alice", bd("100"))));
        assertEquals(ReasonCode.INVALID_AMOUNT, fail(ledger.supply("s", "alice", bd("0"))));
        assertEquals(ReasonCode.INVALID_AMOUNT, fail(ledger.supply("s", "alice", bd("-5"))));
        assertEquals(ReasonCode.UNKNOWN_USER, fail(ledger.withdraw("w", "  ", bd("1"))));

        ok(ledger.supply("s1", "alice", bd("100")));
        assertEquals(ReasonCode.INSUFFICIENT_SUPPLY, fail(ledger.withdraw("w1", "alice", bd("101"))));

        ok(ledger.submitPrice(price(1, "100")));
        ok(ledger.depositCollateral("c1", "bob", bd("10")));
        // 池内仅 100，借 150 现金不足
        assertEquals(ReasonCode.INSUFFICIENT_CASH, fail(ledger.borrow("b1", "bob", bd("150"))));

        // 还款超过债务
        ok(ledger.borrow("b2", "bob", bd("50")));
        assertEquals(ReasonCode.REPAY_EXCEEDS_DEBT, fail(ledger.repay("r1", "bob", bd("51"))));
        // 无债还款
        assertEquals(ReasonCode.REPAY_EXCEEDS_DEBT, fail(ledger.repay("r2", "carol", bd("1"))));
    }

    @Test
    void borrowRejectedWhenHealthFactorBelowOne() {
        TestSupport.section("异常链路-借款导致健康度不足");
        ok(ledger.submitPrice(price(1, "100")));
        ok(ledger.supply("s1", "alice", bd("10000")));
        ok(ledger.depositCollateral("c1", "bob", bd("1"))); // 价值 100，阈值后可支撑债务 80

        OperationResult<?> ok80 = ledger.borrow("b1", "bob", bd("70"));
        ok(ok80);
        log.info("判定依据: 借70后 HF=100*0.8/70={} >= 1，放行",
                ledger.health("bob").healthFactor().toPlainString());

        OperationResult<?> bad = ledger.borrow("b2", "bob", bd("20")); // 总债务90 > 80
        assertEquals(ReasonCode.HEALTH_FACTOR_TOO_LOW, fail(bad));
        log.info("判定依据: 再借20后债务=90, HF=80/90<1，拒绝且不产生任何扣款/债务变化");
        assertEquals(0, bd("70").compareTo(ledger.position("bob").borrowBalance(ledger.pool())),
                "失败请求不得改变债务");
    }

    @Test
    void utilizationDrivesRateCurve() {
        TestSupport.section("利率-利用率越高利率越高");
        ok(ledger.submitPrice(price(1, "100")));
        ok(ledger.supply("s1", "alice", bd("1000")));
        ok(ledger.depositCollateral("c1", "bob", bd("1000")));
        ok(ledger.borrow("b1", "bob", bd("500"))); // u=0.5
        BigDecimal rateAt50 = ledger.pool().utilization();
        clock.advance(1000);
        ok(ledger.borrow("b2", "bob", bd("350"))); // u=0.85 > kink
        log.info("利用率由 {} 升至 {}（跨越 kink=0.8，slope 变陡）",
                rateAt50.toPlainString(), ledger.pool().utilization().toPlainString());
        assertTrue(ledger.pool().utilization().compareTo(bd("0.8")) > 0);
    }
}
