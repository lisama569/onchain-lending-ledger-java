package com.github.lisama569.onchainlendingledgerjava.model;

import com.github.lisama569.onchainlendingledgerjava.domain.Asset;
import com.github.lisama569.onchainlendingledgerjava.domain.PoolState;
import com.github.lisama569.onchainlendingledgerjava.domain.PositionState;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * 可恢复账本快照：每次成功提交一个操作后原子落盘，重启后据此重建全部内存状态。
 * <ul>
 *   <li>pool：资金池（含计息指数）；</li>
 *   <li>positions：全部用户仓位；</li>
 *   <li>priceSnapshot：最新有效价格；</li>
 *   <li>requestLog：已完成请求 requestId -> 结果码（幂等去重，重启后仍生效）。</li>
 * </ul>
 */
public record LedgerSnapshot(
        int version,
        long savedAtMillis,
        PoolState pool,
        Map<String, PositionState> positions,
        PriceSnapshot priceSnapshot,
        Map<String, String> requestLog
) {

    public static final int VERSION = 1;

    public static LedgerSnapshot empty(long now) {
        return new LedgerSnapshot(VERSION, now, PoolState.initial(Asset.CASH.id(), now),
                new HashMap<>(), null, new HashMap<>());
    }
}
