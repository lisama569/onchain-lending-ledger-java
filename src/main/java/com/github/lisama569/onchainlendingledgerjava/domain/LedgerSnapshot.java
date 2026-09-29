package com.github.lisama569.onchainlendingledgerjava.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可恢复账本快照：恢复点 = 全部资金池 + 全部用户账户 + 最新价格帧 + 已完成操作记录。
 */
public class LedgerSnapshot {

    private int version = 1;
    private Map<String, Pool> pools = new HashMap<>();
    private Map<String, UserAccount> accounts = new HashMap<>();
    /** asset -> 最新有效价格帧。 */
    private Map<String, PriceTick> latestPrices = new HashMap<>();
    /** asset -> 近期已接受报价（时间升序，仅保留尾部若干帧，用于抖动排查与恢复）。 */
    private Map<String, List<PriceTick>> priceHistory = new HashMap<>();
    /** requestId -> 处理结果。 */
    private Map<String, OperationRecord> operations = new HashMap<>();
    /** 自启动以来逻辑时钟，用于记录操作顺序（仅展示/排查用）。 */
    private long lastOperationTime;

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Map<String, Pool> getPools() { return pools; }
    public void setPools(Map<String, Pool> pools) { this.pools = pools; }
    public Map<String, UserAccount> getAccounts() { return accounts; }
    public void setAccounts(Map<String, UserAccount> accounts) { this.accounts = accounts; }
    public Map<String, PriceTick> getLatestPrices() { return latestPrices; }
    public void setLatestPrices(Map<String, PriceTick> latestPrices) { this.latestPrices = latestPrices; }
    public Map<String, List<PriceTick>> getPriceHistory() { return priceHistory; }
    public void setPriceHistory(Map<String, List<PriceTick>> priceHistory) { this.priceHistory = priceHistory; }
    public Map<String, OperationRecord> getOperations() { return operations; }
    public void setOperations(Map<String, OperationRecord> operations) { this.operations = operations; }
    public long getLastOperationTime() { return lastOperationTime; }
    public void setLastOperationTime(long lastOperationTime) { this.lastOperationTime = lastOperationTime; }
}
