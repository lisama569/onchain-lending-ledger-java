package com.github.lisama569.onchainlendingledgerjava.service;

import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.domain.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.domain.ErrorCode;
import com.github.lisama569.onchainlendingledgerjava.domain.LedgerSnapshot;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.store.LedgerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价格服务：校验并接受顺序正确的报价，维护各资产最新有效价格帧与快照，
 * 供健康度判定使用；过期 / 乱序 / 异常波动报价被拒绝，绝不参与计算。
 *
 * 接受规则（全部满足才接受，且被拒绝的报价不会落盘、不会改变状态）：
 * 1. price > 0；
 * 2. seq 必须严格大于该资产上一帧 seq（防重放、防乱序）；
 * 3. timestamp 必须严格大于上一帧 timestamp（防乱序）；
 * 4. 与上一帧价格的相对波动不超过 ledger.max-price-deviation（防异常抖动）。
 *
 * 使用规则（健康度/清算取价）：
 * - 没有任何已接受帧 -> INVALID_PRICE；
 * - now - tick.timestamp > TTL -> PRICE_STALE，拒绝清算/借款，避免按过期价格误清算。
 */
@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final int HISTORY_LIMIT = 20;

    private final LedgerStore store;
    private final LedgerProperties properties;

    public PriceService(LedgerStore store, LedgerProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** 提交一帧报价，返回被接受的帧；不满足规则时抛出带明确原因的 BusinessException。 */
    public PriceTick submitTick(String asset, long seq, long timestamp, BigDecimal price) {
        validateText(asset, "asset");
        if (price == null || price.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PRICE,
                    "报价必须为正数: asset=" + asset + ", price=" + price);
        }
        synchronized (store.ledgerLock()) {
            LedgerSnapshot snap = store.load();
            PriceTick previous = snap.getLatestPrices().get(asset);

            if (previous != null) {
                if (seq <= previous.getSeq()) {
                    throw new BusinessException(ErrorCode.PRICE_OUT_OF_ORDER,
                            String.format("报价序号乱序/重复: asset=%s, 收到 seq=%d, 已接受 seq=%d",
                                    asset, seq, previous.getSeq()));
                }
                if (timestamp <= previous.getTimestamp()) {
                    throw new BusinessException(ErrorCode.PRICE_OUT_OF_ORDER,
                            String.format("报价时间戳乱序: asset=%s, 收到 ts=%d, 已接受 ts=%d",
                                    asset, timestamp, previous.getTimestamp()));
                }
                BigDecimal change = price.subtract(previous.getPrice())
                        .divide(previous.getPrice(), 18, RoundingMode.HALF_UP).abs();
                if (change.compareTo(properties.getMaxPriceDeviation()) > 0) {
                    throw new BusinessException(ErrorCode.PRICE_DEVIATION,
                            String.format("报价异常波动被拒绝: asset=%s, old=%s, new=%s, 相对波动=%s, 上限=%s",
                                    asset, previous.getPrice(), price, change, properties.getMaxPriceDeviation()));
                }
            }

            PriceTick tick = new PriceTick(asset, seq, timestamp, price);
            snap.getLatestPrices().put(asset, tick);
            List<PriceTick> history = snap.getPriceHistory().computeIfAbsent(asset, a -> new ArrayList<>());
            history.add(tick);
            while (history.size() > HISTORY_LIMIT) {
                history.remove(0);
            }
            store.persist(snap);
            log.info("[价格] 接受报价 asset={} seq={} ts={} price={}", asset, seq, timestamp, price);
            return tick;
        }
    }

    /** 获取当前有效价格；价格缺失或过期时抛出带明确原因的异常。 */
    public PriceTick requireFreshPrice(String asset, long now) {
        LedgerSnapshot snap = store.load();
        PriceTick tick = snap.getLatestPrices().get(asset);
        if (tick == null) {
            throw new BusinessException(ErrorCode.INVALID_PRICE, "尚无有效报价: asset=" + asset);
        }
        long age = now - tick.getTimestamp();
        if (age > properties.getPriceTtlMillis()) {
            throw new BusinessException(ErrorCode.PRICE_STALE,
                    String.format("报价已过期: asset=%s, 最新帧 ts=%d, 当前=%d, 年龄=%dms, TTL=%dms",
                            asset, tick.getTimestamp(), now, age, properties.getPriceTtlMillis()));
        }
        return tick;
    }

    /** 全部资产的最新价格快照（只读视图），同时标注是否过期。 */
    public Map<String, Object> snapshot(long now) {
        LedgerSnapshot snap = store.load();
        Map<String, Object> view = new LinkedHashMap<>();
        for (Map.Entry<String, PriceTick> e : snap.getLatestPrices().entrySet()) {
            PriceTick t = e.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("asset", t.getAsset());
            item.put("seq", t.getSeq());
            item.put("timestamp", t.getTimestamp());
            item.put("price", t.getPrice());
            item.put("ageMillis", now - t.getTimestamp());
            item.put("stale", now - t.getTimestamp() > properties.getPriceTtlMillis());
            view.put(e.getKey(), item);
        }
        return view;
    }

    /** 某资产近期已接受报价（按时间升序），用于排查抖动。 */
    public List<PriceTick> recentTicks(String asset) {
        LedgerSnapshot snap = store.load();
        List<PriceTick> history = snap.getPriceHistory().get(asset);
        return history == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(history));
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, field + " 不能为空");
        }
    }
}
