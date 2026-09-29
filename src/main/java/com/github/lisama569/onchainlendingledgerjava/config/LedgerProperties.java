package com.github.lisama569.onchainlendingledgerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 账本参数配置（ledger.* 前缀），均有默认值，规则集中可调、可解释。
 *
 * @param snapshotPath         快照文件路径
 * @param priceMaxAgeMillis    价格有效期（超过即视为过期，禁止借款/清算）
 * @param maxPriceDeviationRatio 单笔喂价相对上一口价允许的最大涨跌幅（防异常抖动触发误清算）
 * @param liquidationThreshold 清算阈值：抵押价值 × 阈值 / 债务 < 1 即可被清算（0.80）
 * @param liquidationBonus     清算奖励比例：清算人按还款债务价值的 (1+bonus) 取得抵押（0.05）
 * @param maxLiquidationRatio  单次清算最多偿还当前债务的比例（0.50，部分清算）
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        String snapshotPath,
        Long priceMaxAgeMillis,
        BigDecimal maxPriceDeviationRatio,
        BigDecimal liquidationThreshold,
        BigDecimal liquidationBonus,
        BigDecimal maxLiquidationRatio
) {
    public static LedgerProperties defaults() {
        return new LedgerProperties(
                "data/ledger-snapshot.json",
                120_000L,
                new BigDecimal("0.30"),
                new BigDecimal("0.80"),
                new BigDecimal("0.05"),
                new BigDecimal("0.50"));
    }

    public String snapshotPathOrDefault() {
        return snapshotPath != null ? snapshotPath : defaults().snapshotPath();
    }

    public long priceMaxAgeMillisOrDefault() {
        return priceMaxAgeMillis != null ? priceMaxAgeMillis : defaults().priceMaxAgeMillis();
    }

    public BigDecimal maxPriceDeviationRatioOrDefault() {
        return maxPriceDeviationRatio != null ? maxPriceDeviationRatio : defaults().maxPriceDeviationRatio();
    }

    public BigDecimal liquidationThresholdOrDefault() {
        return liquidationThreshold != null ? liquidationThreshold : defaults().liquidationThreshold();
    }

    public BigDecimal liquidationBonusOrDefault() {
        return liquidationBonus != null ? liquidationBonus : defaults().liquidationBonus();
    }

    public BigDecimal maxLiquidationRatioOrDefault() {
        return maxLiquidationRatio != null ? maxLiquidationRatio : defaults().maxLiquidationRatio();
    }
}
