package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 模拟资产：CASH 为资金池借贷币种（现金），RISK 为抵押品币种。
 * 所有金额一律使用 {@link BigDecimal}，禁止 double，避免浮点误差导致余额不平。
 */
public record Asset(String id, int decimals) {

    public static final Asset CASH = new Asset("CASH", 18);
    public static final Asset RISK = new Asset("RISK", 18);

    private static final Map<String, Asset> REGISTRY = Map.of(CASH.id, CASH, RISK.id, RISK);

    public static Asset of(String id) {
        return REGISTRY.get(id);
    }

    /** 金额零值，统一 scale=18，便于比较与展示。 */
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(18, java.math.RoundingMode.HALF_UP);
    }
}
