package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 已接受的最新有效价格快照（1 个 RISK 折合多少 CASH）。
 * 只有"有效且顺序正确"的喂价才会进入快照，过期判断基于喂价源时间 sourceTimeMillis。
 */
public record PriceSnapshot(
        long lastSeq,
        BigDecimal price,
        long sourceTimeMillis,
        long ingestTimeMillis
) {

    public boolean isFreshAt(long now, long maxAgeMillis) {
        return now - sourceTimeMillis <= maxAgeMillis;
    }

    public long ageMillis(long now) {
        return now - sourceTimeMillis;
    }
}
