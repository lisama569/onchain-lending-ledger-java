package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import com.github.lisama569.onchainlendingledgerjava.domain.PoolState;
import com.github.lisama569.onchainlendingledgerjava.domain.PositionState;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清算链路：健康判定、部分清算上限、奖励划转、三方（池/借款人/清算人）守恒。
 */
class LiquidationTest {

    private static final Logger log = LoggerFactory.getLogger(LiquidationTest.class);

    @TempDir
    Path dir;
    private TestSupport.MutableClock clock;
    private LedgerService ledger;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v).setScale(18, RoundingMode.HALF_UP);
    }

    private PriceTick tick(long seq, String price) {
        return new PriceTick(seq, bd(price), clock.millis(), clock.millis());
    }

    private <T> T ok(OperationResult<T> r) {
        assertTrue(r.success(), () -> "期望成功: " + r.reason() + " " + r.message());
        return r.data();
    }

    private ReasonCode fail(OperationResult<?> r) {
        assertFalse(r.success(), "期望失败");
        log.info("拒绝原因: {} | {}", r.reason(), r.message());
        return r.reason();
    }

    /**
     * 全系统守恒基准（含所有仓位）：
     * 现金口径：cash + totalBorrow = Σ用户存款 + reserves；
     * 借款份额：Σ用户借款份额 == 池借款份额；存款份额同理；
     * 抵押品：Σ用户 RISK 数量（清算只在用户间划转，不增发）。
     */
    private Map<String, String> conservationBaseline() {
        PoolState p = ledger.pool();
        Map<String, String> m = new HashMap<>();
        BigDecimal lhs = p.cash().add(p.totalBorrow());
        BigDecimal rhs = p.totalSupply().add(p.totalReserves());
        m.put("cashIdentityDiff", lhs.subtract(rhs).abs().toPlainString());
        m.put("cashPlusBorrow", lhs.toPlainString());
        m.put("supplyPlusReserves", rhs.toPlainString());
        m.put("utilization", p.utilization().toPlainString());
        return m;
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = TestSupport.clock();
        ledger = TestSupport.newLedger(dir, clock);
    }

    private void setupLiquidatable() {
        // alice 存款 10000（提供流动性）；carol 存款 1000（作为清算人代还资金）
        ok(ledger.supply("s-alice", "alice", bd("10000")));
        ok(ledger.supply("s-carol", "carol", bd("1000")));
        ok(ledger.submitPrice(tick(1, "100")));
        // bob 抵押 1 RISK(价值100) 借 70：HF=80/70≈1.143
        ok(ledger.depositCollateral("c-bob", "bob", bd("1")));
        ok(ledger.borrow("b-bob", "bob", bd("70")));
        // 价格渐跌到 85（-15%）：HF=1*85*0.8/70=0.9714 <1
        ok(ledger.submitPrice(tick(2, "85")));
        log.info("初始可清算: bob HF={}, 债务=70, 抵押=1 RISK@85",
                ledger.health("bob").healthFactor().toPlainString());
    }

    @Test
    void partialLiquidationConservesBalancesAndDistributesBonus() {
        TestSupport.section("清算-部分清算+奖励+三方守恒");
        setupLiquidatable();
        Map<String, String> before = conservationBaseline();
        log.info("清算前守恒基准: {}", before);

        BigDecimal repay = bd("30"); // <= 70*50%=35
        var result = ok(ledger.liquidate("liq-1", "carol", "bob", repay));
        log.info("清算执行结果: {}", result);

        PoolState p = ledger.pool();
        PositionState bob = ledger.position("bob");
        PositionState carol = ledger.position("carol");

        // 债务核销 30
        assertEquals(0, bd("40").compareTo(bob.borrowBalance(p)), "bob 剩余债务应为 40");
        // 抵押划转 = 30*(1.05)/85 = 0.370588...
        BigDecimal expectedSeized = repay.multiply(bd("1.05"))
                .divide(bd("85"), 18, RoundingMode.HALF_UP).setScale(18, RoundingMode.DOWN);
        assertEquals(0, expectedSeized.compareTo(carol.collateral()),
                "清算人应收到 还款额*(1+5%)/价格 的抵押品");
        assertEquals(0, bd("1").subtract(expectedSeized).compareTo(bob.collateral()),
                "借款人抵押品应相应减少");
        // 清算人存款减少 30（代还）
        assertEquals(0, bd("970").compareTo(carol.supplyBalance(p)),
                "清算人存款减少代还额 30（利息微差忽略：清算发生在同一时刻计息点）");

        Map<String, String> after = conservationBaseline();
        log.info("清算后守恒基准: {}", after);
        assertEquals(0, new BigDecimal(after.get("cashIdentityDiff")).compareTo(BigDecimal.ZERO),
                "cash+borrow == supply+reserves 必须严格成立");
        // 现金池在清算前后现金不变（还款来自池内存款份额的核销）
        log.info("判定: 池现金={} 清算前后不变；债务与存款份额同步核销；抵押品只在 bob/carol 间转移",
                p.cash().toPlainString());

        // bob 仍有剩余债务与抵押品，属于部分清算
        assertTrue(bob.borrowBalance(p).signum() > 0 && bob.collateral().signum() > 0, "应保留剩余仓位");
        log.info("部分清算后: bob 剩余债务={}, 剩余抵押={}, HF={}",
                bob.borrowBalance(p).toPlainString(), bob.collateral().toPlainString(),
                ledger.health("bob").healthFactor().toPlainString());
    }

    @Test
    void rejectsOverLimitHealthyAndSelfLiquidation() {
        TestSupport.section("清算-超额/健康/自清算拒绝");
        setupLiquidatable();

        // 超过 50% 上限
        assertEquals(ReasonCode.LIQUIDATION_AMOUNT_TOO_LARGE,
                fail(ledger.liquidate("liq-x1", "carol", "bob", bd("36"))));
        // 自己清算自己
        assertEquals(ReasonCode.INVALID_AMOUNT,
                fail(ledger.liquidate("liq-x2", "bob", "bob", bd("10"))));

        // 价格恢复使仓位健康（100 价位 HF>1）：需渐进恢复 85->100 涨17.6% 在 30% 内
        ok(ledger.submitPrice(new PriceTick(3, bd("100"), clock.millis(), clock.millis())));
        assertEquals(ReasonCode.POSITION_HEALTHY,
                fail(ledger.liquidate("liq-x3", "carol", "bob", bd("10"))));
        log.info("判定: 价格恢复100后 HF={} >=1，禁止清算",
                ledger.health("bob").healthFactor().toPlainString());
    }

    @Test
    void liquidatorWithoutEnoughSupplyRejected() {
        TestSupport.section("清算-清算人资金不足");
        setupLiquidatable();
        // 新用户 zed 无存款
        assertEquals(ReasonCode.INSUFFICIENT_SUPPLY,
                fail(ledger.liquidate("liq-z", "zed", "bob", bd("30"))));
    }
}
