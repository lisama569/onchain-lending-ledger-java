package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 外部喂价：价格以 1e8 定点值（BigDecimal 表示标准单位 CASH/RISK）。
 * seq 单调递增；sourceTimeMillis 为喂价源时间；ingestTimeMillis 为本地接收时间。
 */
public record PriceTick(
        long seq,
        BigDecimal price,
        long sourceTimeMillis,
        long ingestTimeMillis
) {
}
