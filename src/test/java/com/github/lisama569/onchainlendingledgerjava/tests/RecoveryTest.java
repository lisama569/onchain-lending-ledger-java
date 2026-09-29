package com.github.lisama569.onchainlendingledgerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.service.Clock;
import com.github.lisama569.onchainlendingledgerjava.service.InterestRateModel;
import com.github.lisama569.onchainlendingledgerjava.service.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.service.LiquidationService;
import com.github.lisama569.onchainlendingledgerjava.service.PriceService;
import com.github.lisama569.onchainlendingledgerjava.store.LedgerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用恢复测试：
 * 1. 已确认操作落盘后，用全新实例（模拟重启）加载快照，资金池/账户/价格/利息时间点全部恢复；
 * 2. 重启后重复提交旧 requestId 直接重放，不丢失、不重复执行、不二次扣款；
 * 3. 重启后用新 requestId 继续操作，基于恢复后的状态继续累计利息且守恒。
 */
class RecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTest.class);

    @TempDir
    Path tempDir;

    private LedgerTestSupport newSystem(Path snapshotPath, long fixedTime) {
        LedgerProperties props = new LedgerProperties();
        props.setSnapshotPath(snapshotPath.toString());
        props.setPriceTtlMillis(60_000);
        props.setMaxPriceDeviation(new BigDecimal("0.50"));
        props.getCollateralFactors().put("ETH", new BigDecimal("0.75"));
        props.getCollateralFactors().put("USDC", new BigDecimal("0.80"));
        LedgerStore store = new LedgerStore(props, new ObjectMapper());
        Clock clock = new Clock();
        clock.setFixedTime(fixedTime);
        PriceService priceService = new PriceService(store, props);
        LedgerService ledgerService = new LedgerService(store, new InterestRateModel(), priceService, props, clock);
        LiquidationService liquidationService = new LiquidationService(store, ledgerService, priceService, props, clock);
        return new LedgerTestSupport(store, props, clock, priceService, ledgerService, liquidationService);
    }

    @Test
    @DisplayName("重启后状态完整恢复，重复提交不二次扣款，新操作在恢复状态上继续")
    void recoverAndContinue() throws Exception {
        Path snapshot = tempDir.resolve("snapshot.json");
        long t0 = 5_000_000L;
        LedgerTestSupport sys1 = newSystem(snapshot, t0);
        // 建池（模拟 DataInitializer）
        var snap0 = sys1.store.load();
        snap0.getPools().put("ETH", new com.github.lisama569.onchainlendingledgerjava.domain.Pool("ETH", t0));
        snap0.getPools().put("USDC", new com.github.lisama569.onchainlendingledgerjava.domain.Pool("USDC", t0));
        sys1.store.persist(snap0);

        sys1.priceService.submitTick("ETH", 1, t0, new BigDecimal("2000"));
        sys1.priceService.submitTick("USDC", 1, t0, new BigDecimal("1"));
        sys1.ledgerService.supply("req-s1", "alice", "ETH", new BigDecimal("10"));
        sys1.ledgerService.supply("req-s2", "bob", "USDC", new BigDecimal("10000"));
        sys1.ledgerService.borrow("req-b1", "alice", "USDC", new BigDecimal("5000"));

        long beforeSize = Files.size(snapshot);
        assertTrue(beforeSize > 0, "快照文件应已写入");
        log.info("[重启前] 快照大小={} 池状态 cash={} borrows={} deposits={}",
                beforeSize,
                sys1.store.load().getPools().get("USDC").getCash(),
                sys1.store.load().getPools().get("USDC").getTotalBorrows(),
                sys1.store.load().getPools().get("USDC").getTotalDeposits());

        // ---- 模拟重启：全新对象实例，仅共享磁盘快照 ----
        long t1 = t0 + 30L * 24 * 60 * 60 * 1000; // 30 天后恢复
        LedgerTestSupport sys2 = newSystem(snapshot, t1);

        // 资金池/账户恢复
        var poolAfter = sys2.store.load().getPools().get("USDC");
        assertEquals(0, poolAfter.getCash().compareTo(new BigDecimal("5000")), "池现金应恢复为 5000");
        var aliceDebt = sys2.store.load().getAccounts().get("alice").positionOf("USDC").getBorrowShares();
        assertEquals(0, aliceDebt.compareTo(new BigDecimal("5000")), "借款本金应恢复（尚未到新计息点）");
        // 价格帧恢复，且需要推送新帧（旧的在 30 天后已过期——这正是恢复后价格过期保护的体现）
        assertEquals(1, sys2.store.load().getLatestPrices().get("USDC").getSeq());

        // 旧 requestId 重复提交：必须重放而不是再借 5000
        var replay = sys2.ledgerService.borrow("req-b1", "alice", "USDC", new BigDecimal("5000"));
        assertTrue(replay.replayed());
        assertEquals(0, sys2.store.load().getAccounts().get("alice").positionOf("USDC").getBorrowShares()
                .compareTo(new BigDecimal("5000")), "重启后重复请求不得二次扣款");
        log.info("[恢复] 旧请求 req-b1 重放 replayed=true，alice 债务仍为 5000（无二次扣款）");

        // 价格源恢复推送后，系统继续正常工作并在恢复状态上累计利息
        sys2.priceService.submitTick("ETH", 2, t1, new BigDecimal("2000"));
        sys2.priceService.submitTick("USDC", 2, t1, new BigDecimal("1"));
        var health = sys2.ledgerService.healthView("alice", t1);
        log.info("[恢复后继续] alice healthFactor={}（含30天利息的债务）", health.get("healthFactor"));
        var poolWithInterest = sys2.store.load().getPools().get("USDC");
        assertTrue(poolWithInterest.getTotalBorrows().compareTo(new BigDecimal("5000")) > 0,
                "恢复后应在原状态基础上继续累计利息");

        // 守恒
        BigDecimal lhs = poolWithInterest.getCash().add(poolWithInterest.getTotalBorrows());
        assertEquals(0, lhs.compareTo(poolWithInterest.getTotalDeposits()), "恢复并计息后仍须守恒");

        // 新请求正常提交
        sys2.ledgerService.supply("req-s3", "carol", "ETH", new BigDecimal("2"));
        assertEquals(0, sys2.store.load().getAccounts().get("carol").positionOf("ETH").getDepositShares()
                .compareTo(new BigDecimal("2")));
        log.info("[恢复后继续] 新请求 req-s3 正常入账 carol ETH=2，历史幂等记录数={}",
                sys2.store.load().getOperations().size());
    }

    @Test
    @DisplayName("无快照文件时启动得到空账本（不报错）")
    void freshStartWhenNoSnapshot() {
        LedgerTestSupport sys = newSystem(tempDir.resolve("nonexistent.json"), 1_000L);
        assertTrue(sys.store.load().getPools().isEmpty());
        assertTrue(sys.store.load().getOperations().isEmpty());
        log.info("[冷启动] 无快照文件 -> 空账本，加载不报错");
    }
}
