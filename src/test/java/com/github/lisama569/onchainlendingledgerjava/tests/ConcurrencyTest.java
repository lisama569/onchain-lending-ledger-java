package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等：
 * <ul>
 *   <li>多线程并发提取，成功总额不超过存款，现金不透支、余额守恒；</li>
 *   <li>同一 requestId 重复提交（含并发）只生效一次，返回 DUPLICATE_REQUEST 重放。</li>
 * </ul>
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @TempDir
    Path dir;
    private TestSupport.MutableClock clock;
    private LedgerService ledger;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v).setScale(18, RoundingMode.HALF_UP);
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = TestSupport.clock();
        ledger = TestSupport.newLedger(dir, clock);
    }

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        TestSupport.section("并发-多线程提取不透支");
        ledger.supply("seed", "alice", bd("1000"));
        int threads = 32;
        BigDecimal perRequest = bd("100"); // 1000/100 = 10 笔成功，其余必须因余额/现金不足失败

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ReasonCode>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            tasks.add(() -> {
                OperationResult<?> r = ledger.withdraw("w-" + idx, "alice", perRequest);
                return r.success() ? ReasonCode.OK : r.reason();
            });
        }
        List<Future<ReasonCode>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        for (Future<ReasonCode> f : futures) {
            ReasonCode rc = f.get();
            if (rc == ReasonCode.OK) {
                ok.incrementAndGet();
            } else if (rc == ReasonCode.INSUFFICIENT_SUPPLY || rc == ReasonCode.INSUFFICIENT_CASH) {
                insufficient.incrementAndGet();
            } else {
                throw new AssertionError("意外失败原因: " + rc);
            }
        }
        log.info("并发提取结果: 总请求={}, 成功={}, 拒绝(余额/现金不足)={}", threads, ok.get(), insufficient.get());
        assertEquals(10, ok.get(), "1000 存款最多支持 10 笔 100 提取");
        assertEquals(threads - 10, insufficient.get());

        BigDecimal aliceLeft = ledger.position("alice").supplyBalance(ledger.pool());
        BigDecimal cashLeft = ledger.pool().cash();
        log.info("判定: alice 剩余存款={}（应为0）, 池现金={}（应为0）, 不多不少",
                aliceLeft.toPlainString(), cashLeft.toPlainString());
        assertEquals(0, BigDecimal.ZERO.compareTo(aliceLeft));
        assertEquals(0, BigDecimal.ZERO.compareTo(cashLeft));
    }

    @Test
    void duplicateRequestIdExecutesExactlyOnce() throws Exception {
        TestSupport.section("幂等-重复 requestId 只执行一次");
        ledger.supply("seed", "alice", bd("500"));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ReasonCode>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                OperationResult<?> r = ledger.withdraw("same-id", "alice", bd("100"));
                return r.success() ? ReasonCode.OK : r.reason();
            });
        }
        List<Future<ReasonCode>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        for (Future<ReasonCode> f : futures) {
            ReasonCode rc = f.get();
            if (rc == ReasonCode.OK) {
                ok.incrementAndGet();
            } else if (rc == ReasonCode.DUPLICATE_REQUEST) {
                dup.incrementAndGet();
            } else {
                throw new AssertionError("意外原因: " + rc);
            }
        }
        log.info("幂等结果: 总请求={}, 首次成功={}, 重复重放={}", threads, ok.get(), dup.get());
        assertEquals(1, ok.get(), "同一 requestId 必须且只能成功一次");
        assertEquals(threads - 1, dup.get());

        BigDecimal left = ledger.position("alice").supplyBalance(ledger.pool());
        log.info("判定: alice 剩余存款={}（应为400），重复请求没有二次扣款", left.toPlainString());
        assertEquals(0, bd("400").compareTo(left));
    }

    @Test
    void sequentialDuplicateReplaysFirstOutcomeEvenWhenFirstFailed() {
        TestSupport.section("幂等-首次失败也被记录并重放");
        ledger.supply("seed", "alice", bd("100"));
        OperationResult<?> first = ledger.withdraw("fail-id", "alice", bd("999"));
        assertEquals(ReasonCode.INSUFFICIENT_SUPPLY, first.reason());

        OperationResult<?> second = ledger.withdraw("fail-id", "alice", bd("1"));
        log.info("首次失败原因={}, 重复请求返回={}, 详情={}",
                first.reason(), second.reason(), second.details());
        assertEquals(ReasonCode.DUPLICATE_REQUEST, second.reason());
        assertTrue(second.details().get("firstOutcome").toString().contains("INSUFFICIENT_SUPPLY"));
        // 且没有发生 1 元扣款
        assertEquals(0, bd("100").compareTo(ledger.position("alice").supplyBalance(ledger.pool())));
    }
}
