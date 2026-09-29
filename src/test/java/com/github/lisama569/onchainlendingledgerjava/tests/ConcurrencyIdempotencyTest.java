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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等测试：
 * 1. 同一 requestId 串行/并发重复提交只执行一次，不二次扣款；
 * 2. 多用户并发提取总额超过池现金时，成功总额恰好等于池现金，绝不透支；
 * 3. requestId 跨操作复用被拒绝。
 */
class ConcurrencyIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIdempotencyTest.class);

    private LedgerTestSupport t;

    @BeforeEach
    void setUp() {
        t = new LedgerTestSupport();
        long now = t.clock.now();
        t.priceService.submitTick("ETH", 1, now, new BigDecimal("2000"));
        t.priceService.submitTick("USDC", 1, now, new BigDecimal("1"));
    }

    @Test
    @DisplayName("同一requestId串行重复存入只入账一次")
    void duplicateRequestIdReplays() {
        t.ledgerService.supply("dup-1", "alice", "USDC", new BigDecimal("100"));
        var again = t.ledgerService.supply("dup-1", "alice", "USDC", new BigDecimal("100"));
        assertTrue(again.replayed(), "重复提交应标记为重放");
        Position p = t.store.load().getAccounts().get("alice").positionOf("USDC");
        assertEquals(0, p.getDepositShares().compareTo(new BigDecimal("100")),
                "重复请求不得二次入账");
        Pool pool = t.store.load().getPools().get("USDC");
        assertEquals(0, pool.getCash().compareTo(new BigDecimal("100")));
        log.info("[输入] 同一 requestId 两次提交 100 -> [状态变化] 仅入账一次, 余额={} replayed={}",
                p.getDepositShares(), again.replayed());
    }

    @Test
    @DisplayName("同一requestId被并发重复提交时恰好执行一次")
    void concurrentSameRequestIdExecutesOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    var r = t.ledgerService.supply("hot-id", "alice", "USDC", new BigDecimal("50"));
                    if (r.replayed()) {
                        replay.incrementAndGet();
                    } else {
                        success.incrementAndGet();
                    }
                } catch (Throwable th) {
                    errors.add(th);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(errors.isEmpty(), "并发重复提交不应有异常: " + errors);
        assertEquals(1, success.get(), "真正执行的请求必须恰为 1 个");
        assertEquals(threads - 1, replay.get(), "其余全部为重放");
        Position p = t.store.load().getAccounts().get("alice").positionOf("USDC");
        assertEquals(0, p.getDepositShares().compareTo(new BigDecimal("50")), "并发重复请求不得重复入账");
        log.info("[输入] {} 个线程同一 requestId 各存 50 -> [判定依据] 执行={} 重放={} 最终余额={}",
                threads, success.get(), replay.get(), p.getDepositShares());
    }

    @Test
    @DisplayName("10个用户并发提取，总额超过池现金：成功者提取总额恰好=池现金，池不透支")
    void concurrentWithdrawNeverOverdraws() throws Exception {
        // alice 存入 1000；借给 bob 场景下池现金留 400：先存 600 给 carol 并让其借走 600
        t.ledgerService.supply("s1", "alice", "USDC", new BigDecimal("1000"));
        // 制造 600 现金被借出：carol 抵押 ETH 借款
        t.ledgerService.supply("s2", "carol", "ETH", new BigDecimal("10"));
        t.ledgerService.borrow("b1", "carol", "USDC", new BigDecimal("600"));
        Pool before = t.store.load().getPools().get("USDC");
        assertEquals(0, before.getCash().compareTo(new BigDecimal("400")));

        // 10 个"alice 的请求"并发各提 100（其存款=1000 足够，瓶颈在池现金 400）
        int threads = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        BigDecimal withdrawEach = new BigDecimal("100");

        for (int i = 0; i < threads; i++) {
            final String rid = "w-" + i;
            exec.submit(() -> {
                try {
                    barrier.await();
                    t.ledgerService.withdraw(rid, "alice", "USDC", withdrawEach);
                    ok.incrementAndGet();
                } catch (BusinessException ex) {
                    if (ex.getCode() == ErrorCode.INSUFFICIENT_LIQUIDITY) {
                        rejected.incrementAndGet();
                    } else {
                        throw new RuntimeException(ex);
                    }
                } catch (Throwable th) {
                    throw new RuntimeException(th);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        exec.shutdown();

        Pool after = t.store.load().getPools().get("USDC");
        log.info("[输入] {} 并发各提 100（池现金初始 400）-> [状态变化] 成功={} 拒绝={} 池现金后={}",
                threads, ok.get(), rejected.get(), after.getCash());
        assertEquals(4, ok.get(), "恰好 4 笔成功（400/100）");
        assertEquals(6, rejected.get(), "其余 6 笔因流动性不足被拒");
        assertEquals(0, after.getCash().compareTo(BigDecimal.ZERO), "池现金不得为负");
        // 守恒：cash + borrows = deposits
        BigDecimal lhs = after.getCash().add(after.getTotalBorrows());
        assertEquals(0, lhs.compareTo(after.getTotalDeposits()), "并发后资金池必须守恒");
        // 操作记录只含成功的 4 笔提取（失败不登记，允许用原 requestId 稍后重试）
        assertEquals(4, t.store.load().getOperations().keySet().stream()
                .filter(k -> k.startsWith("w-")).count());
    }

    @Test
    @DisplayName("requestId跨操作复用被拒绝（同一id先用于SUPPLY再用于WITHDRAW）")
    void requestIdReuseAcrossOperationsRejected() {
        t.ledgerService.supply("reuse", "alice", "USDC", new BigDecimal("10"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> t.ledgerService.withdraw("reuse", "alice", "USDC", new BigDecimal("5")));
        assertEquals(ErrorCode.INVALID_AMOUNT, ex.getCode());
        log.warn("[输入] requestId=reuse 跨操作复用 -> [判定依据] {}", ex.getMessage());
    }
}
