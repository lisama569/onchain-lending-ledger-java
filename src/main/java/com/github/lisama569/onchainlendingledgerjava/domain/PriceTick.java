package com.github.lisama569.onchainlendingledgerjava.domain;

import java.math.BigDecimal;

/**
 * 价格源推送的一帧报价：资产 + 单调递增序号 + 报价时间 + 价格（以记账本位币计价）。
 */
public class PriceTick {

    private String asset;
    private long seq;
    private long timestamp;
    private BigDecimal price;

    public PriceTick() {
    }

    public PriceTick(String asset, long seq, long timestamp, BigDecimal price) {
        this.asset = asset;
        this.seq = seq;
        this.timestamp = timestamp;
        this.price = price;
    }

    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
