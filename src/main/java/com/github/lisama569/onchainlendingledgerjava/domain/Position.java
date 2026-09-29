package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 单个用户在单个资产上的仓位：存款份额与借款份额。
 * 金额按"当前全局总值"口径保存；每次计息后按比例同步到各仓位。
 */
public class Position {

    /** 存款份额（该用户占 totalDeposits 的部分）。 */
    private BigDecimal depositShares = BigDecimal.ZERO;
    /** 借款份额（该用户占 totalBorrows 的部分）。 */
    private BigDecimal borrowShares = BigDecimal.ZERO;

    public Position() {
    }

    public BigDecimal getDepositShares() { return depositShares; }
    public void setDepositShares(BigDecimal depositShares) { this.depositShares = depositShares; }
    public BigDecimal getBorrowShares() { return borrowShares; }
    public void setBorrowShares(BigDecimal borrowShares) { this.borrowShares = borrowShares; }
}
