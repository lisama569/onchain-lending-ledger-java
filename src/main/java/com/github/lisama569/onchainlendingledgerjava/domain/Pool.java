package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 单一资产的资金池可变状态。
 * 守恒关系（每单位时刻）：cash + totalBorrows = totalDeposits（reserve 为 0，利息全部分给出借方）。
 */
public class Pool {

    /** 资产代码，如 ETH、USDC。 */
    private String asset;
    /** 池内可用现金（未被借出的存款）。 */
    private BigDecimal cash = BigDecimal.ZERO;
    /** 当前未偿还借款本金+已累计利息总额。 */
    private BigDecimal totalBorrows = BigDecimal.ZERO;
    /** 所有存款人的存款份额总值（含利息）。 */
    private BigDecimal totalDeposits = BigDecimal.ZERO;
    /** 上次计息时间（epoch millis）。 */
    private long lastAccrualTime;

    public Pool() {
    }

    public Pool(String asset, long now) {
        this.asset = asset;
        this.lastAccrualTime = now;
    }

    /** 资金利用率 U = totalBorrows / (cash + totalBorrows)。 */
    public BigDecimal utilization() {
        BigDecimal totalAssets = cash.add(totalBorrows);
        if (totalAssets.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return totalBorrows.divide(totalAssets, 18, java.math.RoundingMode.HALF_UP);
    }

    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public BigDecimal getCash() { return cash; }
    public void setCash(BigDecimal cash) { this.cash = cash; }
    public BigDecimal getTotalBorrows() { return totalBorrows; }
    public void setTotalBorrows(BigDecimal totalBorrows) { this.totalBorrows = totalBorrows; }
    public BigDecimal getTotalDeposits() { return totalDeposits; }
    public void setTotalDeposits(BigDecimal totalDeposits) { this.totalDeposits = totalDeposits; }
    public long getLastAccrualTime() { return lastAccrualTime; }
    public void setLastAccrualTime(long lastAccrualTime) { this.lastAccrualTime = lastAccrualTime; }
}
