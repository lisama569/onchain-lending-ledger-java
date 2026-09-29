package com.github.lisama569.onchainlendingledgerjava.tests;

import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.ledger.FileSnapshotStore;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.model.RateModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * 测试夹具：临时快照目录 + 可控时钟 + 真实 FileSnapshotStore/RateModel 组装 LedgerService。
 */
final class TestSupport {

    private static final Logger log = LoggerFactory.getLogger(TestSupport.class);

    static final BigDecimal BONUS = new BigDecimal("0.05");
    static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    static final BigDecimal MAX_LIQ = new BigDecimal("0.50");

    private TestSupport() {
    }

    static MutableClock clock() {
        return new MutableClock(Instant.parse("2026-09-29T00:00:00Z").toEpochMilli());
    }

    static LedgerService newLedger(Path dir, MutableClock clock) throws IOException {
        return newLedger(dir, clock, LedgerProperties.defaults());
    }

    static LedgerService newLedger(Path dir, MutableClock clock, LedgerProperties props) throws IOException {
        Path snapshot = dir.resolve("ledger-snapshot.json");
        FileSnapshotStore store = new FileSnapshotStore(snapshot.toString());
        LedgerService ledger = new LedgerService(store, new RateModel(), props);
        ledger.setClock(clock);
        ledger.init();
        return ledger;
    }

    static LedgerProperties props(String snapshotPath) {
        return new LedgerProperties(snapshotPath, 120_000L, new BigDecimal("0.30"),
                THRESHOLD, BONUS, MAX_LIQ);
    }

    static void section(String name) {
        log.info("==================== {} ====================", name);
    }

    static void expect(Runnable r, Class<? extends Throwable> expected) {
        try {
            r.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return;
            }
            throw new AssertionError("期望异常 " + expected + " 但得到 " + t, t);
        }
        throw new AssertionError("期望异常 " + expected + " 但方法正常返回");
    }

    static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        void setMillis(long m) {
            this.millis = m;
        }
    }
}
