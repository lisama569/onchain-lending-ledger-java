package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.domain.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.domain.ErrorCode;
import com.github.lisama569.onchainlendingledgerjava.domain.Pool;
import com.github.lisama569.onchainlendingledgerjava.domain.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清算测试：
 * - 仓位健康时禁止清算（POSITION_HEALTHY）；
 * - 价格跌破阈值后允许部分清算，奖励分配正确，三方余额守恒；
 * - 单次清算超过 closeFactor 被拒（LIQUIDATION_TOO_LARGE）；
 * - 清算人资金不足（INSUFFICIENT_COLLATERAL）；
 * - 价格过期时不得清算（PRICE_STALE），防止误清算。
 */
class LiquidationTest {

    private static final Logger log = LoggerFactory.getLogger(LiquidationTest.class);
    private static final BigDecimal DUST = new BigDecimal("1E-10");

    private LedgerTestSupport t;

    @BeforeEach
    void setUp() {
        t = new LedgerTestSupport();
        long now = t.clock.now();
        // borrower: 存 1 ETH（2000，CF0.75 => 抵押加权 1500），借 1400 USDC（HF=1500/1400≈1.07 健康）
        t.priceService.submitTick("ETH", 1, now, new BigDecimal("2000"));
        t.priceService.submitTick("USDC", 1, now, new BigDecimal("1"));
        t.ledgerService.supply("sup-b", "borrower", "ETH", new BigDecimal("1"));
        // 池中需要有 USDC 可借：liquidator 先存入
        t.ledgerService.supply("sup-l", "liquidator", "USDC", new BigDecimal("5000"));
        t.ledgerService.borrow("bor-1", "borrower", "USDC", new BigDecimal("1400"));
    }

    private void assertConservation(String asset) {
        Pool p = t.store.load().getPools().get(asset);
        BigDecimal lhs = p.getCash().add(p.getTotalBorrows());
        assertTrue(lhs.subtract(p.getTotalDeposits()).abs().compareTo(DUST) <= 0,
                "余额不平 asset=" + asset + ": " + lhs + " != " + p.getTotalDeposits());
        log.info("[守恒校验] asset={} cash+borrows={} deposits={}", asset, lhs, p.getTotalDeposits());
    }

    @Test
    @DisplayName("仓位健康时禁止清算 -> POSITION_HEALTHY")
    void healthyPositionCannotBeLiquidated() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                t.liquidationService.liquidate("liq-1", "liquidator", "borrower",
                        "USDC", "ETH", new BigDecimal("100")));
        assertEquals(ErrorCode.POSITION_HEALTHY, ex.getCode());
        log.warn("[输入] HF≈1.07 时清算 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("价格下跌导致HF<1后部分清算成功：债务/抵押划转正确且三方守恒")
    void partialLiquidationAfterPriceDrop() {
        long now = t.clock.now() + 1000;
        // ETH 2000 -> 1700（-15%，在允许波动内）：抵押加权=1700*0.75=1275 < 1400，HF≈0.91
        t.priceService.submitTick("ETH", 2, now, new BigDecimal("1700"));

        var healthBefore = t.ledgerService.healthView("borrower", now);
        BigDecimal hfBefore = new BigDecimal(healthBefore.get("healthFactor").toString());
        log.info("[输入] ETH 跌至 1700 -> [判定依据] borrower HF={} (<1 可清算)", hfBefore);
        assertTrue(hfBefore.compareTo(BigDecimal.ONE) < 0);

        // 先取不可变的数值快照（Position 是可变对象，必须先读出 BigDecimal）
        BigDecimal borrowerDebtBefore = t.store.load().getAccounts().get("borrower").positionOf("USDC").getBorrowShares();
        BigDecimal liquidatorUsdcBefore = t.store.load().getAccounts().get("liquidator").positionOf("USDC").getDepositShares();
        BigDecimal borrowerEthBefore = t.store.load().getAccounts().get("borrower").positionOf("ETH").getDepositShares();
        BigDecimal liquidatorEthBefore = t.store.load().getAccounts().get("liquidator").positionOf("ETH").getDepositShares();

        // 偿还 500 USDC（价值500，占总债务价值 1400 的 35.7% < 50% 上限）
        BigDecimal repay = new BigDecimal("500");
        t.liquidationService.liquidate("liq-2", "liquidator", "borrower", "USDC", "ETH", repay);

        Position borrowerDebtAfter = t.store.load().getAccounts().get("borrower").positionOf("USDC");
        Position liquidatorUsdcAfter = t.store.load().getAccounts().get("liquidator").positionOf("USDC");
        Position borrowerEthAfter = t.store.load().getAccounts().get("borrower").positionOf("ETH");
        Position liquidatorEthAfter = t.store.load().getAccounts().get("liquidator").positionOf("ETH");

        // seized = 500 * 1 / 1700 * 1.08
        BigDecimal expectedSeized = new BigDecimal("500")
                .divide(new BigDecimal("1700"), 18, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("1.08")).setScale(18, java.math.RoundingMode.HALF_UP);

        log.info("[状态变化] 清算 500 USDC -> 没收 ETH={} (期望 {})",
                liquidatorEthAfter.getDepositShares(), expectedSeized);
        // 清算先计息，故只断言债务恰好减少 500（利息部分由计息单独覆盖）
        assertEquals(0, borrowerDebtBefore.subtract(borrowerDebtAfter.getBorrowShares())
                .compareTo(new BigDecimal("500")));
        assertEquals(0, liquidatorUsdcAfter.getDepositShares().compareTo(
                liquidatorUsdcBefore.subtract(repay)));
        assertEquals(0, borrowerEthBefore.subtract(borrowerEthAfter.getDepositShares())
                .compareTo(expectedSeized));
        assertEquals(0, liquidatorEthAfter.getDepositShares().subtract(liquidatorEthBefore)
                .compareTo(expectedSeized));

        assertConservation("USDC");
        assertConservation("ETH");
    }

    @Test
    @DisplayName("单次清算超过closeFactor上限 -> LIQUIDATION_TOO_LARGE")
    void closeFactorEnforced() {
        long now = t.clock.now() + 1000;
        t.priceService.submitTick("ETH", 2, now, new BigDecimal("1700"));
        // 偿还 800 > 1400*50%=700
        BusinessException ex = assertThrows(BusinessException.class, () ->
                t.liquidationService.liquidate("liq-3", "liquidator", "borrower",
                        "USDC", "ETH", new BigDecimal("800")));
        assertEquals(ErrorCode.LIQUIDATION_TOO_LARGE, ex.getCode());
        log.warn("[输入] 偿还 800 > 上限 700 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("清算人偿付资金不足 -> INSUFFICIENT_COLLATERAL")
    void liquidatorWithoutFunds() {
        long now = t.clock.now() + 1000;
        t.priceService.submitTick("ETH", 2, now, new BigDecimal("1700"));
        BusinessException ex = assertThrows(BusinessException.class, () ->
                t.liquidationService.liquidate("liq-4", "poor-guy", "borrower",
                        "USDC", "ETH", new BigDecimal("100")));
        assertEquals(ErrorCode.INSUFFICIENT_COLLATERAL, ex.getCode());
        log.warn("[输入] 清算人无 USDC 存款 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("价格过期后禁止清算 -> PRICE_STALE，避免按旧价误清算")
    void noLiquidationWithStalePrice() {
        // borrower 在 2000 价位 HF≈1.07 本来健康；此处模拟报价停更、当前时间远超 TTL
        long stale = t.clock.now() + t.properties.getPriceTtlMillis() + 1;
        t.clock.setFixedTime(stale);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                t.liquidationService.liquidate("liq-5", "liquidator", "borrower",
                        "USDC", "ETH", new BigDecimal("100")));
        assertEquals(ErrorCode.PRICE_STALE, ex.getCode());
        log.warn("[输入] 报价停更超过 TTL 时清算 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("清算请求可幂等重放，不产生二次划转")
    void liquidationIdempotent() {
        long now = t.clock.now() + 1000;
        t.priceService.submitTick("ETH", 2, now, new BigDecimal("1700"));
        t.liquidationService.liquidate("liq-6", "liquidator", "borrower",
                "USDC", "ETH", new BigDecimal("500"));
        BigDecimal debt1 = t.store.load().getAccounts().get("borrower").positionOf("USDC").getBorrowShares();
        BigDecimal eth1 = t.store.load().getAccounts().get("liquidator").positionOf("ETH").getDepositShares();

        var replay = t.liquidationService.liquidate("liq-6", "liquidator", "borrower",
                "USDC", "ETH", new BigDecimal("500"));
        assertTrue(replay.replayed());
        BigDecimal debt2 = t.store.load().getAccounts().get("borrower").positionOf("USDC").getBorrowShares();
        BigDecimal eth2 = t.store.load().getAccounts().get("liquidator").positionOf("ETH").getDepositShares();
        assertEquals(0, debt1.compareTo(debt2));
        assertEquals(0, eth1.compareTo(eth2));
        log.info("[输入] 清算请求 liq-6 重复提交 -> [判定依据] replayed=true, 债务与抵押余额均未二次变化");
    }
}
