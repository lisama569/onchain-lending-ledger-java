package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.domain.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.domain.ErrorCode;
import com.github.lisama569.onchainlendingledgerjava.domain.LedgerSnapshot;
import com.github.lisama569.onchainlendingledgerjava.domain.Pool;
import com.github.lisama569.onchainlendingledgerjava.domain.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心账本正常链路与异常输入：存入/提取/借款/还款、余额守恒、非正数、超额、无借款还款等。
 */
class LedgerCoreFlowTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerCoreFlowTest.class);
    private static final BigDecimal DUST = new BigDecimal("1E-10");

    private LedgerTestSupport t;

    @BeforeEach
    void setUp() {
        t = new LedgerTestSupport();
    }

    private void seedPrices() {
        long now = t.clock.now();
        t.priceService.submitTick("ETH", 1, now, new BigDecimal("2000"));
        t.priceService.submitTick("USDC", 1, now, new BigDecimal("1"));
    }

    private void assertConservation(String asset) {
        Pool p = t.store.load().getPools().get(asset);
        BigDecimal lhs = p.getCash().add(p.getTotalBorrows());
        BigDecimal rhs = p.getTotalDeposits();
        log.info("[守恒校验] asset={} cash={} + borrows={} = {} vs deposits={} (差={})",
                asset, p.getCash(), p.getTotalBorrows(), lhs, rhs, lhs.subtract(rhs).abs());
        assertTrue(lhs.subtract(rhs).abs().compareTo(DUST) <= 0,
                "资金池余额不平: " + lhs + " != " + rhs);
    }

    @Test
    @DisplayName("存入-借款-还款-提取完整链路，全程余额守恒")
    void fullFlowWithConservation() {
        seedPrices();
        long t0 = t.clock.now();

        t.ledgerService.supply("r1", "alice", "ETH", new BigDecimal("10"));
        t.ledgerService.supply("r2", "bob", "USDC", new BigDecimal("10000"));
        assertConservation("ETH");
        assertConservation("USDC");

        // alice 抵押 ETH 借 USDC（抵押价值 10*2000*0.75=15000，可借 10000 以内）
        t.ledgerService.borrow("r3", "alice", "USDC", new BigDecimal("5000"));
        assertConservation("USDC");
        Pool usdc = t.store.load().getPools().get("USDC");
        assertEquals(0, usdc.getCash().compareTo(new BigDecimal("5000.000000000000000000")));
        log.info("[状态变化] alice 借出 5000 USDC 后池现金={}, 总借款={}", usdc.getCash(), usdc.getTotalBorrows());

        // 时间前进 90 天，触发利息累计（下一次操作时）。价格源也必须推送新帧，否则旧价过期。
        long day = 24L * 60 * 60 * 1000;
        long later = t0 + 90 * day;
        t.clock.setFixedTime(later);
        t.priceService.submitTick("ETH", 2, later, new BigDecimal("2000"));
        t.priceService.submitTick("USDC", 2, later, new BigDecimal("1"));
        // bob 取款查询会先计息：检查利息>0 且守恒
        var health = t.ledgerService.healthView("alice", later);
        BigDecimal hf = new BigDecimal(health.get("healthFactor").toString());
        log.info("[计息后] alice healthFactor={} 债务明细={}", hf, health.get("positions"));
        assertConservation("USDC");

        Pool after = t.store.load().getPools().get("USDC");
        assertTrue(after.getTotalBorrows().compareTo(new BigDecimal("5000")) > 0, "90天后应有利息累计");
        // 利用率 0.5 => 年化 2.5%，90 天利息约 5000*0.025*90/365 ≈ 30.82
        BigDecimal expectedInterest = new BigDecimal("5000")
                .multiply(new BigDecimal("0.025")).multiply(new BigDecimal(90))
                .divide(new BigDecimal(365), 8, RoundingMode.HALF_UP);
        BigDecimal actual = after.getTotalBorrows().subtract(new BigDecimal("5000"));
        log.info("[判定依据] 期望利息≈{} 实际利息={}", expectedInterest, actual);
        assertTrue(actual.subtract(expectedInterest).abs().compareTo(new BigDecimal("0.01")) < 0);

        // 全额还款（按当前债务）
        Position debtPos = t.store.load().getAccounts().get("alice").positionOf("USDC");
        BigDecimal debt = debtPos.getBorrowShares();
        t.ledgerService.repay("r4", "alice", "USDC", debt);
        assertConservation("USDC");

        // alice 提走 ETH；USDC 池现金仅覆盖"本金+利息"，bob 只能提走池内现金部分，
        // 然后 alice 还款前其实已全额还款，故池现金 = 5000+利息。校验池整体守恒即可。
        t.ledgerService.withdraw("r5", "alice", "ETH", new BigDecimal("10"));
        Pool usdcBeforeBob = t.store.load().getPools().get("USDC");
        t.ledgerService.withdraw("r6", "bob", "USDC", usdcBeforeBob.getCash());
        assertConservation("ETH");
        assertConservation("USDC");
        Pool eth = t.store.load().getPools().get("ETH");
        Pool usdcEnd = t.store.load().getPools().get("USDC");
        assertEquals(0, eth.getCash().signum());
        // bob 提走现金后池现金归零；totalDeposits 残余为协议内未取利息份额（本例 alice 已无债），
        // 这里 alice 的债务已还清，其借款份额为 0，存款也无 USDC，残余应同样为 0
        assertEquals(0, usdcEnd.getCash().signum());
        assertEquals(0, usdcEnd.getTotalDeposits().compareTo(usdcEnd.getTotalBorrows()));
        log.info("[收尾] 清偿后 ETH 池归零，USDC 池 cash+borrows=deposits 守恒成立 cash={} deposits={} borrows={}",
                usdcEnd.getCash(), usdcEnd.getTotalDeposits(), usdcEnd.getTotalBorrows());
    }

    @Test
    @DisplayName("非正数金额 -> INVALID_AMOUNT")
    void invalidAmount() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> t.ledgerService.supply("x", "alice", "ETH", BigDecimal.ZERO));
        assertEquals(ErrorCode.INVALID_AMOUNT, ex.getCode());
        log.warn("[输入] amount=0 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("不存在的资金池 -> POOL_NOT_FOUND")
    void poolNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> t.ledgerService.supply("x", "alice", "BTC", new BigDecimal("1")));
        assertEquals(ErrorCode.POOL_NOT_FOUND, ex.getCode());
        log.warn("[输入] asset=BTC -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("无报价资产借款被拒 -> INVALID_PRICE")
    void borrowWithoutPrice() {
        t.ledgerService.supply("x1", "alice", "ETH", new BigDecimal("10"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> t.ledgerService.borrow("x2", "alice", "USDC", new BigDecimal("1")));
        assertEquals(ErrorCode.INVALID_PRICE, ex.getCode());
        log.warn("[输入] USDC 无报价即借款 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("超额提取存款 -> INSUFFICIENT_COLLATERAL；池现金不足提取 -> INSUFFICIENT_LIQUIDITY")
    void overWithdrawAndIlliquidity() {
        seedPrices();
        t.ledgerService.supply("x1", "alice", "ETH", new BigDecimal("10"));
        BusinessException over = assertThrows(BusinessException.class,
                () -> t.ledgerService.withdraw("x2", "alice", "ETH", new BigDecimal("11")));
        assertEquals(ErrorCode.INSUFFICIENT_COLLATERAL, over.getCode());
        log.warn("[输入] 提取 11 > 存款 10 -> [判定依据] {}", over.getMessage());

        // bob 存入 100 USDC，借走 100 USDC（需先有抵押；bob 无 ETH，改用 carol 抵押 ETH 借款制造零现金）
        t.ledgerService.supply("x3", "carol", "ETH", new BigDecimal("10")); // 抵押能力 15000
        t.ledgerService.supply("x4", "bob", "USDC", new BigDecimal("100"));
        t.ledgerService.borrow("x5", "carol", "USDC", new BigDecimal("100"));
        // 池现金已为 0，bob 提取自己的 100 存款应被流动性检查拒绝
        BusinessException illiquid = assertThrows(BusinessException.class,
                () -> t.ledgerService.withdraw("x6", "bob", "USDC", new BigDecimal("100")));
        assertEquals(ErrorCode.INSUFFICIENT_LIQUIDITY, illiquid.getCode());
        log.warn("[输入] 池现金=0 时提取 100 -> [判定依据] {}", illiquid.getMessage());
    }

    @Test
    @DisplayName("无借款却还款 -> POSITION_NOT_BORROWED")
    void repayWithoutDebt() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> t.ledgerService.repay("x", "alice", "ETH", new BigDecimal("1")));
        assertEquals(ErrorCode.POSITION_NOT_BORROWED, ex.getCode());
        log.warn("[输入] 无债务还款 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("借款超过抵押能力 -> HEALTH_FACTOR_TOO_LOW")
    void borrowBeyondCollateral() {
        seedPrices();
        t.ledgerService.supply("x1", "alice", "ETH", new BigDecimal("1")); // 抵押价值 1500
        BusinessException ex = assertThrows(BusinessException.class,
                () -> t.ledgerService.borrow("x2", "alice", "USDC", new BigDecimal("2000")));
        assertEquals(ErrorCode.HEALTH_FACTOR_TOO_LOW, ex.getCode());
        log.warn("[输入] 借 2000 > 抵押能力 1500 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("同一时刻重复触发计息不会产生利息（时间不前进则不重复计息）")
    void noDoubleInterestAtSameInstant() {
        seedPrices();
        t.ledgerService.supply("n1", "bob", "USDC", new BigDecimal("10000"));
        t.ledgerService.supply("n2", "carol", "ETH", new BigDecimal("10"));
        t.ledgerService.borrow("n3", "carol", "USDC", new BigDecimal("5000"));
        long now = t.clock.now();
        // 同一时刻多次计息：只有第一次可能有增量；此处 dt=0，全部应为零增量
        t.ledgerService.accrueInterest("USDC", now);
        t.ledgerService.accrueInterest("USDC", now);
        t.ledgerService.accrueInterest("USDC", now);
        Pool usdc = t.store.load().getPools().get("USDC");
        assertEquals(0, usdc.getTotalBorrows().compareTo(new BigDecimal("5000")),
                "同一时刻重复计息不得产生利息");
        log.info("[输入] 同一时刻连续3次计息 -> [判定依据] totalBorrows 仍为 5000，无重复计息");
    }
}
