package com.github.lisama569.onchainlendingledgerjava.ledger;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;
import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.domain.PoolState;
import com.github.lisama569.onchainlendingledgerjava.domain.PositionState;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceSnapshot;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.model.HealthSnapshot;
import com.github.lisama569.onchainlendingledgerjava.model.LedgerSnapshot;
import com.github.lisama569.onchainlendingledgerjava.model.RateModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 账本核心服务。
 * <p>
 * 一致性保证：
 * <ul>
 *   <li>所有状态变更在同一把 {@link ReentrantLock} 内顺序完成
 *       "惰性计息 -> 业务校验 -> 状态变更 -> 守恒检查 -> 原子落盘快照"，
 *       并发请求不会透支、不会重复计息、余额必然守恒；</li>
 *   <li>requestId 全局限流幂等：重复提交直接重放首次结果（成功或失败都不二次执行），重启后仍生效；</li>
 *   <li>每次提交原子覆盖快照文件，重启后内存状态与最后一次成功提交完全一致。</li>
 * </ul>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final SnapshotStore store;
    private final RateModel rateModel;
    private final LedgerProperties props;
    private final ReentrantLock lock = new ReentrantLock(true);

    private volatile PoolState pool;
    private final Map<String, PositionState> positions = new LinkedHashMap<>();
    private volatile PriceSnapshot priceSnapshot;
    private final Map<String, String> requestLog = new LinkedHashMap<>();

    private Clock clock = Clock.systemUTC();

    public LedgerService(SnapshotStore store, RateModel rateModel, LedgerProperties props) {
        this.store = store;
        this.rateModel = rateModel;
        this.props = props;
    }

    /** 测试用：注入可控时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private long now() {
        return clock.millis();
    }

    // ============================ 启动恢复 ============================

    @PostConstruct
    public void init() {
        lock.lock();
        try {
            LedgerSnapshot loaded;
            try {
                loaded = store.load();
            } catch (IOException | RuntimeException ex) {
                throw new IllegalStateException("账本快照损坏或不可读，拒绝以空账本启动以免状态分叉: " + ex.getMessage(), ex);
            }
            if (loaded == null) {
                loaded = LedgerSnapshot.empty(now());
                log.info("以空账本初始化: baseTime={}", loaded.savedAtMillis());
            }
            pool = loaded.pool();
            positions.clear();
            positions.putAll(loaded.positions());
            priceSnapshot = loaded.priceSnapshot();
            requestLog.clear();
            requestLog.putAll(loaded.requestLog());

            // 停机期间利息按经过时间一次性补计（惰性计息天然支持），指数单调，结果与持续在线等价。
            long t = now();
            PoolState before = pool;
            pool = pool.accrue(t, this::ratesFor);
            if (t != before.lastAccrualTimeMillis()) {
                log.info("恢复后补计利息: {}ms, borrowIndex {} -> {}, supplyIndex {} -> {}",
                        t - before.lastAccrualTimeMillis(),
                        before.borrowIndex().toPlainString(), pool.borrowIndex().toPlainString(),
                        before.supplyIndex().toPlainString(), pool.supplyIndex().toPlainString());
            }
            persist("recover", null);
            log.info("账本就绪: positions={}, cash={}, totalSupply={}, totalBorrow={}, reserves={}, price={}, requests={}",
                    positions.size(), pool.cash(), pool.totalSupply(), pool.totalBorrow(),
                    pool.totalReserves(), priceSnapshot == null ? "无" : priceSnapshot.price(), requestLog.size());
        } finally {
            lock.unlock();
        }
    }

    // ============================ 池操作 ============================

    public OperationResult<Map<String, Object>> supply(String requestId, String userId, BigDecimal amount) {
        return execute(requestId, "SUPPLY", userId, amount, (p, pos, details) -> {
            PoolState np = p.withCash(p.cash().add(amount));
            BigDecimal addScaled = PoolState.toScaled(amount, p.supplyIndex());
            PositionState npos = pos.addScaledSupply(addScaled);
            np = np.withScaledTotals(np.totalSupplyScaled().add(addScaled), np.totalBorrowScaled());
            details.put("supplyIndex", p.supplyIndex().toPlainString());
            details.put("mintedScaled", addScaled.toPlainString());
            details.put("cashAfter", np.cash().toPlainString());
            return new Op(np, Map.of(userId, npos), summary("存入", userId, amount, np, npos));
        });
    }

    public OperationResult<Map<String, Object>> withdraw(String requestId, String userId, BigDecimal amount) {
        return execute(requestId, "WITHDRAW", userId, amount, (p, pos, details) -> {
            BigDecimal supplyBalance = pos.supplyBalance(p);
            if (amount.compareTo(supplyBalance) > 0) {
                throw new OpFail(ReasonCode.INSUFFICIENT_SUPPLY,
                        String.format("存款不足: 请求=%s, 存款余额=%s", amount.toPlainString(), supplyBalance.toPlainString()));
            }
            if (amount.compareTo(p.cash()) > 0) {
                throw new OpFail(ReasonCode.INSUFFICIENT_CASH,
                        String.format("资金池现金不足: 请求=%s, 池内现金=%s, 利用率=%s",
                                amount.toPlainString(), p.cash().toPlainString(), p.utilization().toPlainString()));
            }
            // 有借款债务时，提取后仓位必须保持健康，且必须存在新鲜有效价格。
            BigDecimal debt = pos.borrowBalance(p);
            if (debt.signum() > 0) {
                PriceSnapshot ps = requireFreshPrice();
                PoolState trial = p.withCash(p.cash().subtract(amount));
                BigDecimal burnScaled = PoolState.toScaled(amount, p.supplyIndex());
                PositionState trialPos = pos.addScaledSupply(burnScaled.negate());
                HealthSnapshot h = computeHealth(trialPos, trial, ps.price());
                if (h.healthFactor() != null && h.healthFactor().compareTo(BigDecimal.ONE) < 0) {
                    throw new OpFail(ReasonCode.HEALTH_FACTOR_TOO_LOW,
                            "提取后仓位健康度将低于1: " + healthExplanation(h));
                }
            }
            PoolState np = p.withCash(p.cash().subtract(amount));
            BigDecimal burnScaled = PoolState.toScaled(amount, p.supplyIndex());
            PositionState npos = pos.addScaledSupply(burnScaled.negate());
            np = np.withScaledTotals(np.totalSupplyScaled().subtract(burnScaled), np.totalBorrowScaled());
            details.put("burnedScaled", burnScaled.toPlainString());
            details.put("cashAfter", np.cash().toPlainString());
            return new Op(np, Map.of(userId, npos), summary("提取", userId, amount, np, npos));
        });
    }

    public OperationResult<Map<String, Object>> borrow(String requestId, String userId, BigDecimal amount) {
        return execute(requestId, "BORROW", userId, amount, (p, pos, details) -> {
            if (amount.compareTo(p.cash()) > 0) {
                throw new OpFail(ReasonCode.INSUFFICIENT_CASH,
                        String.format("资金池现金不足: 请求=%s, 池内现金=%s, 利用率=%s",
                                amount.toPlainString(), p.cash().toPlainString(), p.utilization().toPlainString()));
            }
            PriceSnapshot ps = requireFreshPrice();
            PoolState trial = p.withCash(p.cash().subtract(amount));
            BigDecimal addScaled = PoolState.toScaled(amount, p.borrowIndex());
            PositionState trialPos = pos.addScaledBorrow(addScaled);
            HealthSnapshot h = computeHealth(trialPos, trial, ps.price());
            if (h.healthFactor() != null && h.healthFactor().compareTo(BigDecimal.ONE) < 0) {
                throw new OpFail(ReasonCode.HEALTH_FACTOR_TOO_LOW,
                        "借款后仓位健康度低于1: " + healthExplanation(h));
            }
            PoolState np = trial;
            PositionState npos = trialPos;
            np = np.withScaledTotals(np.totalSupplyScaled(), np.totalBorrowScaled().add(addScaled));
            details.put("borrowIndex", p.borrowIndex().toPlainString());
            details.put("mintedBorrowScaled", addScaled.toPlainString());
            details.put("healthAfter", h.healthFactor() == null ? "inf" : h.healthFactor().toPlainString());
            details.put("utilizationAfter", np.utilization().toPlainString());
            return new Op(np, Map.of(userId, npos), summary("借款", userId, amount, np, npos));
        });
    }

    public OperationResult<Map<String, Object>> repay(String requestId, String userId, BigDecimal amount) {
        return execute(requestId, "REPAY", userId, amount, (p, pos, details) -> {
            BigDecimal debt = pos.borrowBalance(p);
            if (debt.signum() == 0) {
                throw new OpFail(ReasonCode.REPAY_EXCEEDS_DEBT, "用户无借款债务，不能还款");
            }
            if (amount.compareTo(debt) > 0) {
                throw new OpFail(ReasonCode.REPAY_EXCEEDS_DEBT,
                        String.format("还款额超过债务: 请求=%s, 当前债务=%s", amount.toPlainString(), debt.toPlainString()));
            }
            // 还款现金来自用户的链下资金（模拟直接注入）：cash 与 totalBorrow 同步减少。
            PoolState np = p.withCash(p.cash().add(amount));
            BigDecimal burnScaled = PoolState.toScaled(amount, p.borrowIndex());
            PositionState npos = pos.addScaledBorrow(burnScaled.negate());
            np = np.withScaledTotals(np.totalSupplyScaled(), np.totalBorrowScaled().subtract(burnScaled));
            details.put("burnedBorrowScaled", burnScaled.toPlainString());
            details.put("debtBefore", debt.toPlainString());
            details.put("debtAfter", npos.borrowBalance(np).toPlainString());
            return new Op(np, Map.of(userId, npos), summary("还款", userId, amount, np, npos));
        });
    }

    public OperationResult<Map<String, Object>> depositCollateral(String requestId, String userId, BigDecimal amount) {
        return execute(requestId, "DEPOSIT_COLLATERAL", userId, amount, (p, pos, details) -> {
            PositionState npos = pos.addCollateral(amount);
            details.put("collateralAfter", npos.collateral().toPlainString());
            return new Op(p, Map.of(userId, npos), summary("存入抵押品", userId, amount, p, npos));
        });
    }

    public OperationResult<Map<String, Object>> withdrawCollateral(String requestId, String userId, BigDecimal amount) {
        return execute(requestId, "WITHDRAW_COLLATERAL", userId, amount, (p, pos, details) -> {
            if (amount.compareTo(pos.collateral()) > 0) {
                throw new OpFail(ReasonCode.INSUFFICIENT_COLLATERAL,
                        String.format("抵押品不足: 请求=%s, 抵押余额=%s",
                                amount.toPlainString(), pos.collateral().toPlainString()));
            }
            PositionState trialPos = pos.addCollateral(amount.negate());
            if (pos.borrowBalance(p).signum() > 0) {
                PriceSnapshot ps = requireFreshPrice();
                HealthSnapshot h = computeHealth(trialPos, p, ps.price());
                if (h.healthFactor() != null && h.healthFactor().compareTo(BigDecimal.ONE) < 0) {
                    throw new OpFail(ReasonCode.HEALTH_FACTOR_TOO_LOW,
                            "提取抵押品后仓位健康度将低于1: " + healthExplanation(h));
                }
            }
            details.put("collateralAfter", trialPos.collateral().toPlainString());
            return new Op(p, Map.of(userId, trialPos), summary("提取抵押品", userId, amount, p, trialPos));
        });
    }

    // ============================ 清算 ============================

    public OperationResult<Map<String, Object>> liquidate(String requestId, String liquidatorId,
                                                          String borrowerId, BigDecimal requestedRepayAmount) {
        return execute(requestId, "LIQUIDATE", liquidatorId, requestedRepayAmount, (p, payerPos, details) -> {
            if (Objects.equals(liquidatorId, borrowerId)) {
                throw new OpFail(ReasonCode.INVALID_AMOUNT, "清算人不能清算自己的仓位");
            }
            PositionState borrower = requirePosition(borrowerId);
            PositionState liquidator = positions.getOrDefault(liquidatorId, PositionState.empty(liquidatorId));
            PriceSnapshot ps = requireFreshPrice();

            BigDecimal debt = borrower.borrowBalance(p);
            if (debt.signum() == 0) {
                throw new OpFail(ReasonCode.POSITION_HEALTHY, "借款方无债务，无需清算");
            }
            HealthSnapshot h = computeHealth(borrower, p, ps.price());
            if (!h.liquidatable()) {
                throw new OpFail(ReasonCode.POSITION_HEALTHY, "仓位健康，不允许清算: " + healthExplanation(h));
            }

            BigDecimal maxRepay = debt.multiply(props.maxLiquidationRatioOrDefault())
                    .setScale(PoolState.SCALE, RoundingMode.DOWN);
            if (requestedRepayAmount.compareTo(maxRepay) > 0) {
                throw new OpFail(ReasonCode.LIQUIDATION_AMOUNT_TOO_LARGE,
                        String.format("单次部分清算上限=债务*%s=%s, 请求=%s",
                                props.maxLiquidationRatioOrDefault().toPlainString(),
                                maxRepay.toPlainString(), requestedRepayAmount.toPlainString()));
            }
            // 清算还款现金来自清算人的存款（链上借贷协议标准做法）。
            if (requestedRepayAmount.compareTo(liquidator.supplyBalance(p)) > 0) {
                throw new OpFail(ReasonCode.INSUFFICIENT_SUPPLY,
                        String.format("清算人存款不足以代还: 请求=%s, 清算人存款=%s",
                                requestedRepayAmount.toPlainString(), liquidator.supplyBalance(p).toPlainString()));
            }

            BigDecimal repayScaled = PoolState.toScaled(requestedRepayAmount, p.borrowIndex());
            BigDecimal payerSupplyScaled = PoolState.toScaled(requestedRepayAmount, p.supplyIndex());

            // 清算人取得抵押品数量 = 还款价值 * (1 + bonus) / 价格。
            BigDecimal collateralSeized = requestedRepayAmount
                    .multiply(BigDecimal.ONE.add(props.liquidationBonusOrDefault()))
                    .divide(ps.price(), 40, RoundingMode.HALF_UP)
                    .setScale(PoolState.SCALE, RoundingMode.DOWN);
            if (collateralSeized.compareTo(borrower.collateral()) > 0) {
                throw new OpFail(ReasonCode.INSUFFICIENT_COLLATERAL,
                        String.format("抵押物不足以按奖励价划转: 需=%s, 借款人抵押=%s（极端资不抵债，模拟协议拒绝坏账清算）",
                                collateralSeized.toPlainString(), borrower.collateral().toPlainString()));
            }

            PoolState np = p.withScaledTotals(
                    p.totalSupplyScaled().subtract(payerSupplyScaled),
                    p.totalBorrowScaled().subtract(repayScaled));
            // 现金不移动：清算人存款对应的现金此前已在池中，债务份额核销后池负债下降，恒等式仍成立。
            PositionState nBorrower = borrower
                    .addScaledBorrow(repayScaled.negate())
                    .addCollateral(collateralSeized.negate());
            PositionState nLiquidator = liquidator
                    .addScaledSupply(payerSupplyScaled.negate())
                    .addCollateral(collateralSeized);

            HealthSnapshot hAfter = computeHealth(nBorrower, np, ps.price());
            details.put("price", ps.price().toPlainString());
            details.put("healthBefore", h.healthFactor().toPlainString());
            details.put("repayAmount", requestedRepayAmount.toPlainString());
            details.put("liquidationBonus", props.liquidationBonusOrDefault().toPlainString());
            details.put("collateralSeized", collateralSeized.toPlainString());
            details.put("debtAfter", nBorrower.borrowBalance(np).toPlainString());
            details.put("healthAfter", hAfter.healthFactor() == null ? "inf" : hAfter.healthFactor().toPlainString());
            details.put("basis", "healthFactor=抵押价值*阈值/债务 < 1 触发; 清算量<=债务*"
                    + props.maxLiquidationRatioOrDefault() + "; 抵押划转=还款额*(1+奖励)/价格");

            Map<String, Object> changed = new LinkedHashMap<>();
            changed.put("type", "清算");
            changed.put("borrowerId", borrowerId);
            changed.put("liquidatorId", liquidatorId);
            changed.put("repayAmount", requestedRepayAmount.toPlainString());
            changed.put("collateralSeized", collateralSeized.toPlainString());
            changed.put("borrowerDebtAfter", nBorrower.borrowBalance(np).toPlainString());
            changed.put("borrowerCollateralAfter", nBorrower.collateral().toPlainString());
            changed.put("liquidatorSupplyAfter", nLiquidator.supplyBalance(np).toPlainString());
            changed.put("liquidatorCollateralAfter", nLiquidator.collateral().toPlainString());
            changed.put("poolCashAfter", np.cash().toPlainString());
            return new Op(np, Map.of(borrowerId, nBorrower, liquidatorId, nLiquidator), changed);
        });
    }

    // ============================ 喂价 ============================

    public OperationResult<PriceSnapshot> submitPrice(PriceTick tick) {
        lock.lock();
        try {
            pool = pool.accrue(now(), this::ratesFor);

            if (tick == null || tick.price() == null
                    || tick.price().signum() <= 0
                    || tick.price().compareTo(new BigDecimal("1E15")) > 0) {
                return OperationResult.fail("price#" + (tick == null ? "null" : tick.seq()),
                        ReasonCode.PRICE_INVALID, "价格为空/非正/超出合理量级，拒绝参与计算");
            }
            long t = now();
            if (tick.sourceTimeMillis() > t + 1000) {
                return OperationResult.fail("price#" + tick.seq(), ReasonCode.PRICE_INVALID,
                        "喂价源时间在未来，拒绝");
            }
            if (priceSnapshot != null && tick.seq() <= priceSnapshot.lastSeq()) {
                return OperationResult.fail("price#" + tick.seq(), ReasonCode.PRICE_SEQ_OLD,
                        String.format("价格序号过旧: 收到seq=%d, 最新已接受seq=%d（乱序/重复喂价被忽略）",
                                tick.seq(), priceSnapshot.lastSeq()));
            }
            if (priceSnapshot != null) {
                BigDecimal old = priceSnapshot.price();
                BigDecimal changeRatio = tick.price().subtract(old).abs()
                        .divide(old, 18, RoundingMode.HALF_UP);
                if (changeRatio.compareTo(props.maxPriceDeviationRatioOrDefault()) > 0) {
                    return OperationResult.fail("price#" + tick.seq(), ReasonCode.PRICE_DEVIATION,
                            String.format("价格异常波动: 旧价=%s, 新价=%s, 涨跌幅=%s > 上限=%s（拒绝以防误清算）",
                                    old.toPlainString(), tick.price().toPlainString(),
                                    changeRatio.toPlainString(),
                                    props.maxPriceDeviationRatioOrDefault().toPlainString()));
                }
            }
            PriceSnapshot accepted = new PriceSnapshot(tick.seq(), tick.price(),
                    tick.sourceTimeMillis(), tick.ingestTimeMillis() == 0 ? t : tick.ingestTimeMillis());
            priceSnapshot = accepted;
            persist("PRICE", null);
            log.info("喂价已接受: seq={}, price={}, sourceAge={}ms",
                    accepted.lastSeq(), accepted.price(), t - accepted.sourceTimeMillis());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("seq", accepted.lastSeq());
            details.put("price", accepted.price().toPlainString());
            details.put("sourceAgeMillis", t - accepted.sourceTimeMillis());
            return OperationResult.ok("price#" + accepted.lastSeq(), accepted, details);
        } finally {
            lock.unlock();
        }
    }

    // ============================ 查询 ============================

    public PoolState pool() {
        lock.lock();
        try {
            PoolState accrued = pool.accrue(now(), this::ratesFor);
            if (accrued != pool) {
                pool = accrued;
            }
            return pool;
        } finally {
            lock.unlock();
        }
    }

    public PositionState position(String userId) {
        lock.lock();
        try {
            pool = pool.accrue(now(), this::ratesFor);
            return positions.getOrDefault(userId, PositionState.empty(userId));
        } finally {
            lock.unlock();
        }
    }

    public PriceSnapshot price() {
        return priceSnapshot;
    }

    public HealthSnapshot health(String userId) {
        lock.lock();
        try {
            pool = pool.accrue(now(), this::ratesFor);
            PositionState pos = positions.get(userId);
            if (pos == null) {
                throw new OpFail(ReasonCode.UNKNOWN_USER, "用户不存在: " + userId);
            }
            BigDecimal px = priceSnapshot == null ? null : priceSnapshot.price();
            return computeHealth(pos, pool, px);
        } finally {
            lock.unlock();
        }
    }

    public List<HealthSnapshot> allHealth() {
        lock.lock();
        try {
            pool = pool.accrue(now(), this::ratesFor);
            BigDecimal px = priceSnapshot == null ? null : priceSnapshot.price();
            List<HealthSnapshot> out = new ArrayList<>();
            for (PositionState pos : positions.values()) {
                out.add(computeHealth(pos, pool, px));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    public LedgerSnapshot snapshot() {
        lock.lock();
        try {
            return new LedgerSnapshot(LedgerSnapshot.VERSION, now(), pool,
                    new LinkedHashMap<>(positions), priceSnapshot, new LinkedHashMap<>(requestLog));
        } finally {
            lock.unlock();
        }
    }

    public void resetAll() {
        lock.lock();
        try {
            long t = now();
            pool = PoolState.initial(com.github.lisama569.onchainlendingledgerjava.domain.Asset.CASH.id(), t);
            positions.clear();
            priceSnapshot = null;
            requestLog.clear();
            persist("RESET", null);
            log.warn("账本已重置为空状态");
        } finally {
            lock.unlock();
        }
    }

    public String requestOutcome(String requestId) {
        lock.lock();
        try {
            return requestLog.get(requestId);
        } finally {
            lock.unlock();
        }
    }

    // ============================ 内部实现 ============================

    @FunctionalInterface
    private interface OpBody {
        Op apply(PoolState p, PositionState pos, Map<String, Object> details);
    }

    private record Op(PoolState pool, Map<String, PositionState> changed, Map<String, Object> summary) {
    }

    private static final class OpFail extends RuntimeException {
        private final ReasonCode reason;

        OpFail(ReasonCode reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private OperationResult<Map<String, Object>> execute(String requestId, String opType,
                                                         String userId, BigDecimal amount, OpBody body) {
        if (requestId == null || requestId.isBlank()) {
            return OperationResult.fail(null, ReasonCode.INVALID_AMOUNT, "requestId 不能为空（幂等键缺失）");
        }
        if (userId == null || userId.isBlank()) {
            return OperationResult.fail(requestId, ReasonCode.UNKNOWN_USER, "userId 不能为空");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > PoolState.SCALE) {
            return OperationResult.fail(requestId, ReasonCode.INVALID_AMOUNT,
                    String.format("金额非法: %s（必须为正数且小数位<=%d）", amount, PoolState.SCALE));
        }
        lock.lock();
        try {
            // 1) 幂等重放：重复请求返回首次结论，绝不二次执行/二次扣款。
            String prior = requestLog.get(requestId);
            if (prior != null) {
                log.info("幂等重放: requestId={}, 首次结果={}", requestId, prior);
                return OperationResult.fail(requestId, ReasonCode.DUPLICATE_REQUEST,
                        "重复请求，已重放首次结果: " + prior,
                        Map.of("reasonCode", ReasonCode.DUPLICATE_REQUEST.name(), "firstOutcome", prior));
            }

            // 2) 全局惰性计息（一次操作一次计息，重复请求不计息）。
            PoolState p = pool.accrue(now(), this::ratesFor);
            PositionState pos = positions.getOrDefault(userId, PositionState.empty(userId));

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("op", opType);
            details.put("userId", userId);
            details.put("amount", amount.toPlainString());
            details.put("utilizationBefore", p.utilization().toPlainString());
            details.put("borrowIndex", p.borrowIndex().toPlainString());
            details.put("supplyIndex", p.supplyIndex().toPlainString());

            PoolState np;
            Map<String, PositionState> changed;
            Map<String, Object> summary;
            try {
                Op op = body.apply(p, pos, details);
                np = op.pool();
                changed = op.changed();
                summary = op.summary();
            } catch (OpFail fail) {
                // 业务失败：计息推进与 requestId 记录在同一快照原子落盘；
                // 重复提交将命中幂等重放，返回同一个可区分原因，不会再次尝试执行或扣款。
                pool = p;
                requestLog.put(requestId, fail.reason.name());
                persist(opType + "_FAILED", null);
                log.info("业务拒绝: requestId={}, op={}, reason={}, msg={}",
                        requestId, opType, fail.reason.name(), fail.getMessage());
                return OperationResult.fail(requestId, fail.reason, fail.getMessage(),
                        Map.of("reasonCode", fail.reason.name(), "op", opType, "message", fail.getMessage(),
                                "utilization", p.utilization().toPlainString()));
            }

            // 3) 守恒检查：cash + totalBorrow = totalSupply + totalReserves。
            checkConservation(np, changed);

            // 4) 提交内存状态 + 原子落盘（requestLog 与状态同一快照，要么都生效要么都不生效）。
            pool = np;
            changed.forEach((uid, npos) -> {
                if (npos.scaledSupply().signum() == 0 && npos.scaledBorrow().signum() == 0
                        && npos.collateral().signum() == 0) {
                    positions.remove(uid);
                } else {
                    positions.put(uid, npos);
                }
            });
            requestLog.put(requestId, ReasonCode.OK.name());
            persist(opType, null);

            log.info("操作成功: requestId={}, op={}, user={}, amount={}, cash={}, totalSupply={}, totalBorrow={}, reserves={}",
                    requestId, opType, userId, amount.toPlainString(), pool.cash().toPlainString(),
                    pool.totalSupply().toPlainString(), pool.totalBorrow().toPlainString(),
                    pool.totalReserves().toPlainString());
            return OperationResult.ok(requestId, summary, details);
        } finally {
            lock.unlock();
        }
    }

    private void checkConservation(PoolState np, Map<String, PositionState> changed) {
        // 合并 changed 后的份额总量
        BigDecimal sumSupplyScaled = BigDecimal.ZERO;
        BigDecimal sumBorrowScaled = BigDecimal.ZERO;
        BigDecimal sumCollateral = BigDecimal.ZERO;
        for (Map.Entry<String, PositionState> e : positions.entrySet()) {
            PositionState v = changed.getOrDefault(e.getKey(), e.getValue());
            sumSupplyScaled = sumSupplyScaled.add(v.scaledSupply());
            sumBorrowScaled = sumBorrowScaled.add(v.scaledBorrow());
            sumCollateral = sumCollateral.add(v.collateral());
        }
        for (Map.Entry<String, PositionState> e : changed.entrySet()) {
            if (!positions.containsKey(e.getKey())) {
                sumSupplyScaled = sumSupplyScaled.add(e.getValue().scaledSupply());
                sumBorrowScaled = sumBorrowScaled.add(e.getValue().scaledBorrow());
                sumCollateral = sumCollateral.add(e.getValue().collateral());
            }
        }
        BigDecimal lhs = np.cash().add(PoolState.fromScaled(sumBorrowScaled, np.borrowIndex()));
        BigDecimal rhs = PoolState.fromScaled(sumSupplyScaled, np.supplyIndex()).add(np.totalReserves());
        BigDecimal diff = lhs.subtract(rhs).abs();
        if (diff.compareTo(PoolState.DUST) > 0) {
            throw new ConservationException(String.format(
                    "资金守恒被破坏: cash+borrow=%s, supply+reserves=%s, 差额=%s",
                    lhs.toPlainString(), rhs.toPlainString(), diff.toPlainString()));
        }
        BigDecimal tbScaled = np.totalBorrowScaled();
        if (sumBorrowScaled.compareTo(tbScaled) != 0) {
            throw new ConservationException(String.format(
                    "借款份额不平: 用户合计=%s, 池记录=%s", sumBorrowScaled.toPlainString(), tbScaled.toPlainString()));
        }
        BigDecimal tsScaled = np.totalSupplyScaled();
        if (sumSupplyScaled.compareTo(tsScaled) != 0) {
            throw new ConservationException(String.format(
                    "存款份额不平: 用户合计=%s, 池记录=%s", sumSupplyScaled.toPlainString(), tsScaled.toPlainString()));
        }
        log.debug("守恒检查通过: cash+borrow={} = supply+reserves={}, 抵押品合计={}",
                lhs.toPlainString(), rhs.toPlainString(), sumCollateral.toPlainString());
    }

    private void persist(String phase, String dummy) {
        try {
            store.save(new LedgerSnapshot(LedgerSnapshot.VERSION, now(), pool,
                    new LinkedHashMap<>(positions), priceSnapshot, new LinkedHashMap<>(requestLog)));
        } catch (IOException ex) {
            throw new OpFail(ReasonCode.INTERNAL_ERROR, "快照落盘失败，为避免状态分叉拒绝继续: " + ex.getMessage());
        }
    }

    private PositionState requirePosition(String userId) {
        PositionState p = positions.get(userId);
        if (p == null) {
            throw new OpFail(ReasonCode.UNKNOWN_USER, "用户不存在: " + userId);
        }
        return p;
    }

    private PriceSnapshot requireFreshPrice() {
        long t = now();
        if (priceSnapshot == null) {
            throw new OpFail(ReasonCode.PRICE_STALE, "尚无有效价格，无法判定仓位健康度");
        }
        if (!priceSnapshot.isFreshAt(t, props.priceMaxAgeMillisOrDefault())) {
            throw new OpFail(ReasonCode.PRICE_STALE,
                    String.format("价格已过期: 最新价格seq=%d, price=%s, 源时间年龄=%dms > 有效期=%dms（禁止借款/提取/清算，防止误清算）",
                            priceSnapshot.lastSeq(), priceSnapshot.price().toPlainString(),
                            priceSnapshot.ageMillis(t), props.priceMaxAgeMillisOrDefault()));
        }
        return priceSnapshot;
    }

    private HealthSnapshot computeHealth(PositionState pos, PoolState p, BigDecimal price) {
        BigDecimal debt = pos.borrowBalance(p);
        if (price == null) {
            String reason = "无有效价格，无法计算健康度";
            return new HealthSnapshot(pos.userId(), pos.collateral(), null, null, debt,
                    props.liquidationThresholdOrDefault(), null, false, reason);
        }
        BigDecimal collateralValue = pos.collateral().multiply(price)
                .setScale(PoolState.SCALE, RoundingMode.HALF_UP);
        BigDecimal threshold = props.liquidationThresholdOrDefault();
        if (debt.signum() == 0) {
            return HealthSnapshot.healthyNoDebt(pos.userId(), pos.collateral(), price, collateralValue, threshold);
        }
        BigDecimal hf = collateralValue.multiply(threshold)
                .divide(debt, 18, RoundingMode.HALF_UP);
        boolean liquidatable = hf.compareTo(BigDecimal.ONE) < 0;
        String reason = String.format(
                "HF=抵押%s*价格%s*阈值%s/债务%s=%s %s 1",
                pos.collateral().toPlainString(), price.toPlainString(), threshold.toPlainString(),
                debt.toPlainString(), hf.toPlainString(), liquidatable ? "<" : ">=");
        return new HealthSnapshot(pos.userId(), pos.collateral(), price, collateralValue, debt,
                threshold, hf, liquidatable, reason);
    }

    private static String healthExplanation(HealthSnapshot h) {
        return String.format("HF=%s (抵押价值=%s*阈值%s, 债务=%s)",
                h.healthFactor() == null ? "inf" : h.healthFactor().toPlainString(),
                h.collateralValue() == null ? "n/a" : h.collateralValue().toPlainString(),
                h.liquidationThreshold().toPlainString(), h.debtValue().toPlainString());
    }

    private BigDecimal[] ratesFor(BigDecimal utilization) {
        return new BigDecimal[]{
                rateModel.borrowRatePerSecond(utilization),
                rateModel.supplyRatePerSecond(utilization)};
    }

    private Map<String, Object> summary(String type, String userId, BigDecimal amount,
                                        PoolState np, PositionState npos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("userId", userId);
        m.put("amount", amount.toPlainString());
        m.put("supplyAfter", npos.supplyBalance(np).toPlainString());
        m.put("borrowAfter", npos.borrowBalance(np).toPlainString());
        m.put("collateralAfter", npos.collateral().toPlainString());
        m.put("poolCashAfter", np.cash().toPlainString());
        m.put("utilizationAfter", np.utilization().toPlainString());
        return m;
    }
}
