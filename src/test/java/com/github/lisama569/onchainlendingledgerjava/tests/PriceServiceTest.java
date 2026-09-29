package com.github.lisama569.onchainlendingledgerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.domain.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.domain.ErrorCode;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.service.Clock;
import com.github.lisama569.onchainlendingledgerjava.service.PriceService;
import com.github.lisama569.onchainlendingledgerjava.store.LedgerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 价格服务单测：乱序/重复帧拒绝、异常波动拒绝、过期取价拒绝，正常帧接受且可查询。
 */
class PriceServiceTest {

    private static final Logger log = LoggerFactory.getLogger(PriceServiceTest.class);

    private Path snapshotPath;
    private PriceService priceService;
    private LedgerProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        snapshotPath = Files.createTempDirectory("ledger-price-test").resolve("snapshot.json");
        properties = new LedgerProperties();
        properties.setSnapshotPath(snapshotPath.toString());
        properties.setPriceTtlMillis(60_000);
        properties.setMaxPriceDeviation(new BigDecimal("0.50"));
        LedgerStore store = new LedgerStore(properties, new ObjectMapper());
        priceService = new PriceService(store, properties);
    }

    @Test
    @DisplayName("正常报价被接受，可按新鲜度取到")
    void acceptValidTick() {
        PriceTick tick = priceService.submitTick("ETH", 1, 1_000, new BigDecimal("2000"));
        log.info("[输入] asset=ETH seq=1 ts=1000 price=2000 -> [状态变化] accepted={}", tick.getPrice());
        PriceTick got = priceService.requireFreshPrice("ETH", 2_000);
        assertEquals(1, got.getSeq());
        assertEquals(0, got.getPrice().compareTo(new BigDecimal("2000")));
    }

    @Test
    @DisplayName("重复或乱序seq/ts的报价被拒绝，错误码 PRICE_OUT_OF_ORDER")
    void rejectOutOfOrder() {
        priceService.submitTick("ETH", 10, 1_000, new BigDecimal("2000"));

        BusinessException dupSeq = assertThrows(BusinessException.class,
                () -> priceService.submitTick("ETH", 10, 2_000, new BigDecimal("2010")));
        assertEquals(ErrorCode.PRICE_OUT_OF_ORDER, dupSeq.getCode());
        log.warn("[输入] seq=10 重复 -> [判定依据] {}", dupSeq.getMessage());

        BusinessException oldTs = assertThrows(BusinessException.class,
                () -> priceService.submitTick("ETH", 11, 900, new BigDecimal("2010")));
        assertEquals(ErrorCode.PRICE_OUT_OF_ORDER, oldTs.getCode());
        log.warn("[输入] ts=900 早于已接受帧 -> [判定依据] {}", oldTs.getMessage());

        // 被拒绝的帧不得改变状态：最新仍是 seq=10
        assertEquals(10, priceService.requireFreshPrice("ETH", 2_000).getSeq());
    }

    @Test
    @DisplayName("相对波动超过上限的报价被拒绝，错误码 PRICE_DEVIATION")
    void rejectDeviation() {
        priceService.submitTick("ETH", 1, 1_000, new BigDecimal("2000"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> priceService.submitTick("ETH", 2, 2_000, new BigDecimal("4000")));
        assertEquals(ErrorCode.PRICE_DEVIATION, ex.getCode());
        log.warn("[输入] 2000 -> 4000 (+100%) -> [判定依据] {}", ex.getMessage());
        assertEquals(1, priceService.requireFreshPrice("ETH", 2_000).getSeq());
    }

    @Test
    @DisplayName("非正数报价被拒绝，错误码 INVALID_PRICE")
    void rejectNonPositive() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> priceService.submitTick("ETH", 1, 1_000, BigDecimal.ZERO));
        assertEquals(ErrorCode.INVALID_PRICE, ex.getCode());
        log.warn("[输入] price=0 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("超过TTL的价格取价失败，错误码 PRICE_STALE，防止按旧价误清算")
    void stalePriceRejected() {
        priceService.submitTick("ETH", 1, 1_000, new BigDecimal("2000"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> priceService.requireFreshPrice("ETH", 1_000 + properties.getPriceTtlMillis() + 1));
        assertEquals(ErrorCode.PRICE_STALE, ex.getCode());
        log.warn("[输入] now 超过 TTL -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("边界：恰好在TTL内的价格仍可用，恰好TTL也算过期")
    void ttlBoundary() {
        priceService.submitTick("ETH", 1, 1_000, new BigDecimal("2000"));
        PriceTick fresh = priceService.requireFreshPrice("ETH", 1_000 + properties.getPriceTtlMillis());
        assertEquals(1, fresh.getSeq());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> priceService.requireFreshPrice("ETH", 1_000 + properties.getPriceTtlMillis() + 1));
        assertTrue(ex.getCode() == ErrorCode.PRICE_STALE);
        log.info("[输入] age=TTL 可用, age=TTL+1 过期 -> [判定依据] {}", ex.getMessage());
    }

    @Test
    @DisplayName("价格抖动边界：恰好波动50%接受，超过50%拒绝；连续小步波动可接受")
    void deviationBoundaryAndSmallSteps() {
        priceService.submitTick("ETH", 1, 1_000, new BigDecimal("2000"));
        // 恰好 +50% -> 3000，边界为"不超过"，接受
        priceService.submitTick("ETH", 2, 2_000, new BigDecimal("3000"));
        assertEquals(2, priceService.requireFreshPrice("ETH", 2_000).getSeq());
        log.info("[输入] 2000 -> 3000 恰好+50% -> [判定依据] 边界内，接受");
        // 3000 -> 4501 超过 +50%，拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> priceService.submitTick("ETH", 3, 3_000, new BigDecimal("4501")));
        assertEquals(ErrorCode.PRICE_DEVIATION, ex.getCode());
        log.warn("[输入] 3000 -> 4501 超过+50% -> [判定依据] {}", ex.getMessage());
        // 插针后继续用合理帧 4500（+50%）应被接受，证明单次抖动不阻塞价格源
        priceService.submitTick("ETH", 4, 4_000, new BigDecimal("4500"));
        assertEquals(4, priceService.requireFreshPrice("ETH", 4_000).getSeq());
        log.info("[输入] 异常帧之后合理帧 4500 正常接受 -> 价格源未被单次抖动阻塞");
    }
}
