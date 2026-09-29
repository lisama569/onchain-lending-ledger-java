package com.github.lisama569.onchainlendingledgerjava.config;

import com.github.lisama569.onchainlendingledgerjava.domain.LedgerSnapshot;
import com.github.lisama569.onchainlendingledgerjava.domain.Pool;
import com.github.lisama569.onchainlendingledgerjava.service.Clock;
import com.github.lisama569.onchainlendingledgerjava.store.LedgerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 启动引导：
 * 1. 触发快照加载（恢复已有资金池/账户/价格/幂等记录）；
 * 2. 仅在全新空账本时创建引导资金池——重启恢复时绝不重建、不清空，
 *    保证未完成或重复提交的操作在恢复后语义不变。
 */
@Component
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final LedgerStore ledgerStore;
    private final LedgerProperties properties;
    private final Clock clock;

    public DataInitializer(LedgerStore ledgerStore, LedgerProperties properties, Clock clock) {
        this.ledgerStore = ledgerStore;
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        synchronized (ledgerStore.ledgerLock()) {
            LedgerSnapshot snap = ledgerStore.load();
            if (snap.getPools().isEmpty() && properties.getBootstrapAssets() != null
                    && !properties.getBootstrapAssets().isBlank()) {
                long now = clock.now();
                for (String asset : properties.getBootstrapAssets().split(",")) {
                    String trimmed = asset.trim();
                    if (!trimmed.isEmpty() && !snap.getPools().containsKey(trimmed)) {
                        snap.getPools().put(trimmed, new Pool(trimmed, now));
                        log.info("[引导] 创建资金池 asset={}", trimmed);
                    }
                }
                ledgerStore.persist(snap);
            } else {
                log.info("[引导] 恢复到既有账本，跳过资金池初始化");
            }
        }
    }
}
