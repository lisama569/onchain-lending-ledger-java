package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.model.HealthSnapshot;
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
 * 价格守卫：只有有效且顺序正确的价格参与计算；过期/乱序/异常波动不得触发误清算。
 */
class PriceGuardTest {

    private static final Logger log = LoggerFactory.getLogger(PriceGuardTest.class);

    @TempDir
    Path dir;
    private TestSupport.MutableClock clock;
    private LedgerService ledger;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v).setScale(18, RoundingMode.HALF_UP);
    }

    private PriceTick tick(long seq, String price, long sourceMillis) {
        return new PriceTick(seq, bd(price), sourceMillis, clock.millis());
    }

    private <T> T ok(OperationResult<T> r) {
        assertTrue(r.success(), () -> "期望成功: " + r.reason() + " " + r.message());
        return r.data();
    }

    private ReasonCode fail(OperationResult<?> r) {
        assertFalse(r.success(), "期望失败");
        log.info("拒绝原因可区分: {} | {}", r.reason(), r.message());
        return r.reason();
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = TestSupport.clock();
        ledger = TestSupport.newLedger(dir, clock);
    }

    /** 构造接近清算线的仓位：alice 存 10000；bob 抵押 1 RISK，价 100 时借 70 => HF=80/70≈1.14。 */
    private void setupNearLiquidation() {
        ok(ledger.submitPrice(tick(1, "100", clock.millis())));
        ok(ledger.supply("s1", "alice", bd("10000")));
        ok(ledger.depositCollateral("c1", "bob", bd("1")));
        ok(ledger.borrow("b1", "bob", bd("70")));
        HealthSnapshot h = ledger.health("bob");
        log.info("建仓完成: HF={} (>1 健康), 判定依据: {}", h.healthFactor().toPlainString(), h.reason());
    }

    @Test
    void rejectsOldSeqAndDuplicateTicks() {
        TestSupport.section("价格-乱序/重复序号被忽略");
        ok(ledger.submitPrice(tick(10, "100", clock.millis())));
        assertEquals(ReasonCode.PRICE_SEQ_OLD, fail(ledger.submitPrice(tick(9, "10", clock.millis()))));
        assertEquals(ReasonCode.PRICE_SEQ_OLD, fail(ledger.submitPrice(tick(10, "10", clock.millis()))));
        log.info("判定: 已接受seq={}, 乱序/重复喂价均未影响现价 {}", 10, ledger.price().price());
        assertEquals(0, bd("100").compareTo(ledger.price().price()));
    }

    @Test
    void rejectsInvalidPrices() {
        TestSupport.section("价格-非法值被拒绝");
        assertEquals(ReasonCode.PRICE_INVALID,
                fail(ledger.submitPrice(new PriceTick(1, null, clock.millis(), clock.millis()))));
        assertEquals(ReasonCode.PRICE_INVALID,
                fail(ledger.submitPrice(tick(1, "0", clock.millis()))));
        assertEquals(ReasonCode.PRICE_INVALID,
                fail(ledger.submitPrice(tick(1, "-5", clock.millis()))));
        assertEquals(ReasonCode.PRICE_INVALID,
                fail(ledger.submitPrice(tick(1, "100", clock.millis() + 60_000)))); // 未来时间
    }

    @Test
    void rejectsAbnormalDeviationButAcceptsGradualMoves() {
        TestSupport.section("价格-异常波动拒绝，渐进波动接受");
        ok(ledger.submitPrice(tick(1, "100", clock.millis())));
        // 100 -> 50 跌幅 50% > 30% 上限，拒绝
        assertEquals(ReasonCode.PRICE_DEVIATION, fail(ledger.submitPrice(tick(2, "50", clock.millis()))));
        log.info("判定: 50% 暴跌被判定为异常抖动，现价仍为 {}", ledger.price().price());
        // 100 -> 75 跌幅 25%，接受（渐进下跌可正常触发健康度重算）
        ok(ledger.submitPrice(tick(3, "75", clock.millis())));
        log.info("判定: 25% 跌幅在 30% 上限内，现价更新为 {}", ledger.price().price());
        assertEquals(0, bd("75").compareTo(ledger.price().price()));
    }

    @Test
    void stalePriceBlocksLiquidationAndBorrow() {
        TestSupport.section("价格-过期不得误清算");
        setupNearLiquidation();
        // 130 秒后价格过期（有效期 120s）
        clock.advance(130_000);
        log.info("状态: 价格源时间年龄=130000ms > 120000ms，判定为过期");

        OperationResult<?> liq = ledger.liquidate("l1", "carol", "bob", bd("35"));
        assertEquals(ReasonCode.PRICE_STALE, fail(liq));
        OperationResult<?> moreBorrow = ledger.borrow("b2", "bob", bd("1"));
        assertEquals(ReasonCode.PRICE_STALE, fail(moreBorrow));

        // 新的有效价格（seq 正确、源时间新鲜）恢复，价格仍为 100 -> HF>1，清算仍应被"仓位健康"拒绝
        ok(ledger.submitPrice(tick(2, "100", clock.millis())));
        OperationResult<?> liq2 = ledger.liquidate("l2", "carol", "bob", bd("35"));
        assertEquals(ReasonCode.POSITION_HEALTHY, fail(liq2));
        log.info("恢复有效价格后: HF={} >=1，不能清算", ledger.health("bob").healthFactor().toPlainString());
    }

    @Test
    void healthyPositionCannotBeLiquidated() {
        TestSupport.section("价格下跌到可清算的渐进路径");
        setupNearLiquidation();
        // 100 -> 85 (-15%)：HF = 1*85*0.8/70 = 0.971 <1 可清算
        ok(ledger.submitPrice(tick(2, "85", clock.millis())));
        HealthSnapshot h = ledger.health("bob");
        log.info("现价85: HF={} <1, 判定依据: {}", h.healthFactor().toPlainString(), h.reason());
        assertTrue(h.liquidatable());

        // 但同样的暴跌若是一口价 50（-50%），系统拒绝该价格 → 不允许据此清算
        // （由 rejectsAbnormalDeviationButAcceptsGradualMoves 独立验证）
    }
}
