package com.github.lisama569.onchainlendingledgerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.service.Clock;
import com.github.lisama569.onchainlendingledgerjava.service.InterestRateModel;
import com.github.lisama569.onchainlendingledgerjava.service.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.service.LiquidationService;
import com.github.lisama569.onchainlendingledgerjava.service.PriceService;
import com.github.lisama569.onchainlendingledgerjava.store.LedgerStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试夹具：构造一套使用临时快照文件、固定时钟的完整服务（不走 Spring 容器，单测更快更确定）。
 */
class LedgerTestSupport {

    final Path snapshotPath;
    final LedgerProperties properties;
    final LedgerStore store;
    final Clock clock;
    final PriceService priceService;
    final LedgerService ledgerService;
    final LiquidationService liquidationService;

    LedgerTestSupport(LedgerStore store, LedgerProperties properties, Clock clock,
                      PriceService priceService, LedgerService ledgerService,
                      LiquidationService liquidationService) {
        this.snapshotPath = null;
        this.properties = properties;
        this.store = store;
        this.clock = clock;
        this.priceService = priceService;
        this.ledgerService = ledgerService;
        this.liquidationService = liquidationService;
    }

    LedgerTestSupport() {
        try {
            snapshotPath = Files.createTempDirectory("ledger-core-test").resolve("snapshot.json");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        properties = new LedgerProperties();
        properties.setSnapshotPath(snapshotPath.toString());
        properties.setPriceTtlMillis(60_000);
        properties.setMaxPriceDeviation(new BigDecimal("0.50"));
        properties.getCollateralFactors().put("ETH", new BigDecimal("0.75"));
        properties.getCollateralFactors().put("USDC", new BigDecimal("0.80"));

        store = new LedgerStore(properties, new ObjectMapper());
        clock = new Clock();
        clock.setFixedTime(1_000_000L);
        priceService = new PriceService(store, properties);
        InterestRateModel rateModel = new InterestRateModel();
        ledgerService = new LedgerService(store, rateModel, priceService, properties, clock);
        liquidationService = new LiquidationService(store, ledgerService, priceService, properties, clock);

        // 手工建池（DataInitializer 是 Spring 组件，纯单测中直接复刻其建池动作）
        var snap = store.load();
        long now = clock.now();
        snap.getPools().put("ETH", new com.github.lisama569.onchainlendingledgerjava.domain.Pool("ETH", now));
        snap.getPools().put("USDC", new com.github.lisama569.onchainlendingledgerjava.domain.Pool("USDC", now));
        store.persist(snap);
    }
}
