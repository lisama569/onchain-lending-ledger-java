package com.github.lisama569.onchainlendingledgerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账本可调参数（application.properties 可覆盖）。
 */
@Component
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {

    /** 快照文件路径。 */
    private String snapshotPath = "data/ledger-snapshot.json";
    /** 空账本启动时引导创建的资金池（逗号分隔资产代码）。 */
    private String bootstrapAssets = "ETH,USDC";
    /** 价格有效期（毫秒），超过则判定过期。 */
    private long priceTtlMillis = 60_000;
    /** 相邻有效报价最大相对波动（如 0.5 表示 50%），超过拒绝。 */
    private BigDecimal maxPriceDeviation = new BigDecimal("0.50");
    /** 抵押品折现率（LTV），按资产配置，默认 0.75。 */
    private Map<String, BigDecimal> collateralFactors = new LinkedHashMap<>();
    /** 清算阈值：健康度低于该值才允许清算，默认 1.0。 */
    private BigDecimal liquidationThreshold = BigDecimal.ONE;
    /** 清算奖励比例（清算人折扣），默认 0.08。 */
    private BigDecimal liquidationBonus = new BigDecimal("0.08");
    /** 单次清算最多覆盖债务比例，默认 50%。 */
    private BigDecimal maxCloseFactor = new BigDecimal("0.50");

    public String getSnapshotPath() { return snapshotPath; }
    public void setSnapshotPath(String snapshotPath) { this.snapshotPath = snapshotPath; }
    public String getBootstrapAssets() { return bootstrapAssets; }
    public void setBootstrapAssets(String bootstrapAssets) { this.bootstrapAssets = bootstrapAssets; }
    public long getPriceTtlMillis() { return priceTtlMillis; }
    public void setPriceTtlMillis(long priceTtlMillis) { this.priceTtlMillis = priceTtlMillis; }
    public BigDecimal getMaxPriceDeviation() { return maxPriceDeviation; }
    public void setMaxPriceDeviation(BigDecimal maxPriceDeviation) { this.maxPriceDeviation = maxPriceDeviation; }
    public Map<String, BigDecimal> getCollateralFactors() { return collateralFactors; }
    public void setCollateralFactors(Map<String, BigDecimal> collateralFactors) { this.collateralFactors = collateralFactors; }
    public BigDecimal getLiquidationThreshold() { return liquidationThreshold; }
    public void setLiquidationThreshold(BigDecimal liquidationThreshold) { this.liquidationThreshold = liquidationThreshold; }
    public BigDecimal getLiquidationBonus() { return liquidationBonus; }
    public void setLiquidationBonus(BigDecimal liquidationBonus) { this.liquidationBonus = liquidationBonus; }
    public BigDecimal getMaxCloseFactor() { return maxCloseFactor; }
    public void setMaxCloseFactor(BigDecimal maxCloseFactor) { this.maxCloseFactor = maxCloseFactor; }
}
