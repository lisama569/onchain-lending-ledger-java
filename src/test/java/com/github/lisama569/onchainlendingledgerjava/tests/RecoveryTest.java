package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恢复语义：
 * <ul>
 *   <li>重启后池/仓位/价格/幂等日志完全恢复；</li>
 *   <li>停机期间利息一次性补计，结果与在线一致；</li>
 *   <li>重启后重复提交历史 requestId 只重放不二次执行；新 requestId 正常推进。</li>
 * </ul>
 */
class RecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTest.class);

    @TempDir
    Path dir;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v).setScale(18, RoundingMode.HALF_UP);
    }

    private <T> T ok(OperationResult<T> r) {
        assertTrue(r.success(), () -> "期望成功: " + r.reason() + " " + r.message());
        return r.data();
    }

    @Test
    void restartRestoresStateAccruesDowntimeInterestAndKeepsIdempotency() throws Exception {
        TestSupport.section("恢复-重启/补息/幂等");
        TestSupport.MutableClock clock = TestSupport.clock();

        // ---- 第一阶段：旧进程 ----
        LedgerService first = TestSupport.newLedger(dir, clock);
        ok(first.submitPrice(new PriceTick(1, bd("100"), clock.millis(), clock.millis())));
        ok(first.supply("s1", "alice", bd("1000")));
        ok(first.depositCollateral("c1", "bob", bd("20")));
        ok(first.borrow("b1", "bob", bd("800"))); // u=0.8
        // 一个失败的请求也要被持久化
        OperationResult<?> failed = first.withdraw("w-fail", "alice", bd("10000"));
        assertEquals(ReasonCode.INSUFFICIENT_SUPPLY, failed.reason());
        log.info("旧进程: 建仓完成 u=0.8，失败请求 w-fail 已入幂等日志: {}", first.requestOutcome("w-fail"));

        // ---- 模拟停机 1 年 ----
        clock.advance(365L * 24 * 3600 * 1000);
        log.info("模拟停机 365 天……");

        // ---- 第二阶段：新进程从同一快照路径启动 ----
        LedgerService second = TestSupport.newLedger(dir, clock);
        BigDecimal bobDebt = second.position("bob").borrowBalance(second.pool());
        BigDecimal aliceSupply = second.position("alice").supplyBalance(second.pool());
        log.info("新进程恢复: bob债务={}（期望880），alice存款={}（期望1072），抵押={}，价格seq={}",
                bobDebt.toPlainString(), aliceSupply.toPlainString(),
                second.position("bob").collateral().toPlainString(), second.price().lastSeq());
        assertEquals(0, bd("880").compareTo(bobDebt), "停机利息必须补计");
        assertEquals(0, bd("1072").compareTo(aliceSupply));
        assertEquals(0, bd("20").compareTo(second.position("bob").collateral()));
        assertEquals(1, second.price().lastSeq());

        // 重复提交历史成功/失败 requestId：均重放，不二次执行
        OperationResult<?> dupBorrow = second.borrow("b1", "bob", bd("800"));
        assertEquals(ReasonCode.DUPLICATE_REQUEST, dupBorrow.reason());
        assertEquals("OK", dupBorrow.details().get("firstOutcome"));
        OperationResult<?> dupFail = second.withdraw("w-fail", "alice", bd("1"));
        assertEquals(ReasonCode.DUPLICATE_REQUEST, dupFail.reason());
        assertEquals("INSUFFICIENT_SUPPLY", dupFail.details().get("firstOutcome"));
        log.info("恢复后重复请求: b1/w-fail 均命中幂等重放，债务仍为880未二次借款");
        assertEquals(0, bd("880").compareTo(second.position("bob").borrowBalance(second.pool())));

        // 新请求正常推进：bob 还款 880（注入现金），alice 取款 1072
        ok(second.repay("r1", "bob", bd("880")));
        ok(second.withdraw("w1", "alice", bd("1072")));
        log.info("恢复后新请求正常: 池现金={}, 储备={}, 债务={}",
                second.pool().cash().toPlainString(),
                second.pool().totalReserves().toPlainString(),
                second.pool().totalBorrow().toPlainString());
        assertEquals(0, bd("8").compareTo(second.pool().cash()));
        assertEquals(0, bd("8").compareTo(second.pool().totalReserves()));
    }

    @Test
    void snapshotFileIsTheDurableSourceOfTruth() throws Exception {
        TestSupport.section("恢复-快照即为事实来源");
        TestSupport.MutableClock clock = TestSupport.clock();
        LedgerService a = TestSupport.newLedger(dir, clock);
        ok(a.submitPrice(new PriceTick(1, bd("100"), clock.millis(), clock.millis())));
        ok(a.supply("s1", "alice", bd("42")));

        Path snapshot = dir.resolve("ledger-snapshot.json");
        assertTrue(java.nio.file.Files.exists(snapshot), "每次提交后快照文件必须存在");
        String json = java.nio.file.Files.readString(snapshot);
        log.info("快照文件内容（关键片段）: cash/金额以字符串存储，requestLog 包含已完成请求: 长度={}",
                json.length());
        assertTrue(json.contains("\"alice\"") && json.contains("s1"), "快照必须包含仓位与幂等日志");

        // 第三个实例（模拟再次重启）读同一文件
        LedgerService b = TestSupport.newLedger(dir, clock);
        assertEquals(0, bd("42").compareTo(b.position("alice").supplyBalance(b.pool())));
        log.info("第三次启动恢复 alice 存款=42，成功");
    }
}
