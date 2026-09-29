package com.github.lisama569.onchainlendingledgerjava.service;

import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.domain.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.domain.ErrorCode;
import com.github.lisama569.onchainlendingledgerjava.domain.LedgerSnapshot;
import com.github.lisama569.onchainlendingledgerjava.domain.OperationRecord;
import com.github.lisama569.onchainlendingledgerjava.domain.Pool;
import com.github.lisama569.onchainlendingledgerjava.domain.Position;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.domain.UserAccount;
import com.github.lisama569.onchainlendingledgerjava.store.LedgerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账本核心服务：资金池存入/提取/借款/还款、利息累计与健康度查询。
 *
 * 并发与一致性：
 * 1. 所有写操作在 {@link LedgerStore#ledgerLock()} 这把全局锁内串行执行
 *    "校验 -> 计息 -> 记账 -> 落盘 -> 幂等登记"，并发请求不会透支、不会重复计息；
 * 2. 利息按资金池总量精确计算（HALF_UP，18 位小数），再按份额比例分摊到各仓位，
 *    任意时刻对单个资产恒有 cash + totalBorrows = totalDeposits（误差仅末位舍入）；
 * 3. 成功操作携带 requestId 登记并随快照落盘，重复提交直接重放原结果，不二次扣款。
 *
 * 业务校验失败一律抛出带 {@link ErrorCode} 的 {@link BusinessException}，错误码可区分。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    static final int MONEY_SCALE = 18;
    static final int VALUE_SCALE = 8;
    private static final BigDecimal MILLIS_PER_YEAR = new BigDecimal(365L * 24 * 60 * 60 * 1000);

    private final LedgerStore store;
    private final InterestRateModel rateModel;
    private final PriceService priceService;
    private final LedgerProperties properties;
    private final Clock clock;

    public LedgerService(LedgerStore store, InterestRateModel rateModel, PriceService priceService,
                         LedgerProperties properties, Clock clock) {
        this.store = store;
        this.rateModel = rateModel;
        this.priceService = priceService;
        this.properties = properties;
        this.clock = clock;
    }

    // ----------------------------- 写操作 -----------------------------

    public OpResult supply(String requestId, String userId, String asset, BigDecimal amount) {
        validateId(requestId, "requestId");
        validateId(userId, "userId");
        validateId(asset, "asset");
        validateAmount(amount);
        long now = clock.now();
        synchronized (store.ledgerLock()) {
            OpResult replay = checkReplay(requestId, "SUPPLY");
            if (replay != null) {
                return replay;
            }
            LedgerSnapshot snap = store.load();
            Pool pool = requirePool(snap, asset);
            accrueInterest(pool, snap, now);

            Position pos = requireAccount(snap, userId).positionOf(asset);
            pos.setDepositShares(scale(pos.getDepositShares().add(amount)));
            pool.setCash(scale(pool.getCash().add(amount)));
            pool.setTotalDeposits(scale(pool.getTotalDeposits().add(amount)));

            log.info("[存入] requestId={} user={} asset={} amount={} | 池后状态 cash={} deposits={} borrows={}",
                    requestId, userId, asset, amount, pool.getCash(), pool.getTotalDeposits(), pool.getTotalBorrows());
            commit(snap, requestId, "SUPPLY", now);
            return OpResult.ok(requestId, "SUPPLY");
        }
    }

    public OpResult withdraw(String requestId, String userId, String asset, BigDecimal amount) {
        validateId(requestId, "requestId");
        validateId(userId, "userId");
        validateId(asset, "asset");
        validateAmount(amount);
        long now = clock.now();
        synchronized (store.ledgerLock()) {
            OpResult replay = checkReplay(requestId, "WITHDRAW");
            if (replay != null) {
                return replay;
            }
            LedgerSnapshot snap = store.load();
            Pool pool = requirePool(snap, asset);
            accrueInterest(pool, snap, now);

            UserAccount account = getAccount(snap, userId);
            Position pos = account == null ? null : account.getPositions().get(asset);
            BigDecimal deposited = pos == null ? BigDecimal.ZERO : pos.getDepositShares();
            if (amount.compareTo(deposited) > 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_COLLATERAL,
                        String.format("提取超过存款: user=%s asset=%s, 请求=%s, 存款=%s",
                                userId, asset, amount, deposited));
            }
            if (amount.compareTo(pool.getCash()) > 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_LIQUIDITY,
                        String.format("资金池流动性不足: asset=%s, 请求=%s, 可用现金=%s, 利用率=%s",
                                asset, amount, pool.getCash(), pool.utilization()));
            }

            pos.setDepositShares(scale(deposited.subtract(amount)));
            pool.setCash(scale(pool.getCash().subtract(amount)));
            pool.setTotalDeposits(scale(pool.getTotalDeposits().subtract(amount)));

            HealthCheck after = computeHealth(snap, userId, now);
            if (after.borrowedValue.signum() > 0
                    && after.healthFactor.compareTo(properties.getLiquidationThreshold()) < 0) {
                throw new BusinessException(ErrorCode.HEALTH_FACTOR_TOO_LOW,
                        String.format("提取后仓位将不健康，拒绝: user=%s asset=%s amount=%s, 提取后 HF=%s",
                                userId, asset, amount, after.healthFactor));
            }

            log.info("[提取] requestId={} user={} asset={} amount={} | 池后状态 cash={} deposits={} borrows={}",
                    requestId, userId, asset, amount, pool.getCash(), pool.getTotalDeposits(), pool.getTotalBorrows());
            commit(snap, requestId, "WITHDRAW", now);
            return OpResult.ok(requestId, "WITHDRAW");
        }
    }

    public OpResult borrow(String requestId, String userId, String asset, BigDecimal amount) {
        validateId(requestId, "requestId");
        validateId(userId, "userId");
        validateId(asset, "asset");
        validateAmount(amount);
        long now = clock.now();
        synchronized (store.ledgerLock()) {
            OpResult replay = checkReplay(requestId, "BORROW");
            if (replay != null) {
                return replay;
            }
            LedgerSnapshot snap = store.load();
            Pool pool = requirePool(snap, asset);
            // 借款必须有该资产的新鲜报价（借出资产按市价计入债务价值）
            priceService.requireFreshPrice(asset, now);
            accrueInterest(pool, snap, now);

            // 先做"试记账 + 健康度"判定：抵押能力不足时优先给出 HEALTH_FACTOR_TOO_LOW
            UserAccount account = requireAccount(snap, userId);
            Position pos = account.positionOf(asset);
            BigDecimal originalBorrow = pos.getBorrowShares();
            pos.setBorrowShares(scale(originalBorrow.add(amount)));
            pool.setTotalBorrows(scale(pool.getTotalBorrows().add(amount)));
            HealthCheck after = computeHealth(snap, userId, now);
            if (after.healthFactor.compareTo(properties.getLiquidationThreshold()) < 0) {
                // 回滚试记账
                pos.setBorrowShares(originalBorrow);
                pool.setTotalBorrows(scale(pool.getTotalBorrows().subtract(amount)));
                throw new BusinessException(ErrorCode.HEALTH_FACTOR_TOO_LOW,
                        String.format("借款后健康度不足，拒绝: user=%s asset=%s amount=%s, 抵押价值=%s, 债务价值=%s, HF=%s",
                                userId, asset, amount, after.adjustedCollateralValue, after.borrowedValue,
                                after.healthFactor));
            }
            // 健康度通过后再检查流动性
            if (amount.compareTo(pool.getCash()) > 0) {
                pos.setBorrowShares(originalBorrow);
                pool.setTotalBorrows(scale(pool.getTotalBorrows().subtract(amount)));
                throw new BusinessException(ErrorCode.INSUFFICIENT_LIQUIDITY,
                        String.format("资金池流动性不足: asset=%s, 请求借款=%s, 可用现金=%s",
                                asset, amount, pool.getCash()));
            }

            pool.setCash(scale(pool.getCash().subtract(amount)));

            log.info("[借款] requestId={} user={} asset={} amount={} | 池后状态 cash={} deposits={} borrows={} HF={}",
                    requestId, userId, asset, amount, pool.getCash(), pool.getTotalDeposits(),
                    pool.getTotalBorrows(), after.healthFactor);
            commit(snap, requestId, "BORROW", now);
            return OpResult.ok(requestId, "BORROW");
        }
    }

    public OpResult repay(String requestId, String userId, String asset, BigDecimal amount) {
        validateId(requestId, "requestId");
        validateId(userId, "userId");
        validateId(asset, "asset");
        validateAmount(amount);
        long now = clock.now();
        synchronized (store.ledgerLock()) {
            OpResult replay = checkReplay(requestId, "REPAY");
            if (replay != null) {
                return replay;
            }
            LedgerSnapshot snap = store.load();
            Pool pool = requirePool(snap, asset);
            accrueInterest(pool, snap, now);

            UserAccount account = getAccount(snap, userId);
            Position pos = account == null ? null : account.getPositions().get(asset);
            BigDecimal debt = pos == null ? BigDecimal.ZERO : pos.getBorrowShares();
            if (debt.signum() == 0) {
                throw new BusinessException(ErrorCode.POSITION_NOT_BORROWED,
                        "无该资产借款可偿还: user=" + userId + ", asset=" + asset);
            }
            BigDecimal paid = amount.min(debt); // 多还部分按清偿全部处理（不记到账外，避免余额不平）
            if (amount.compareTo(debt) > 0) {
                log.info("[还款] requestId={} user={} asset={} 多还 amount={} > 债务={}，按全额清偿处理",
                        requestId, userId, asset, amount, debt);
            }

            pos.setBorrowShares(scale(debt.subtract(paid)));
            pool.setCash(scale(pool.getCash().add(paid)));
            pool.setTotalBorrows(scale(pool.getTotalBorrows().subtract(paid)));

            log.info("[还款] requestId={} user={} asset={} paid={} 剩余债务={} | 池后状态 cash={} borrows={}",
                    requestId, userId, asset, paid, pos.getBorrowShares(), pool.getCash(), pool.getTotalBorrows());
            commit(snap, requestId, "REPAY", now);
            return OpResult.ok(requestId, "REPAY");
        }
    }

    // ----------------------------- 利息 -----------------------------

    /**
     * 触发一次利息累计。计息在全局锁内、且先于任何业务变更执行，
     * 因此无论多少并发操作到达，同一时间区间最多计息一次（随先拿到锁的操作完成），杜绝重复计息。
     */
    public void accrueInterest(String asset, long now) {
        validateId(asset, "asset");
        synchronized (store.ledgerLock()) {
            Pool pool = requirePool(store.load(), asset);
            accrueInterest(pool, store.load(), now);
            store.persist(store.load());
        }
    }

    /**
     * 计息核心（调用方必须已持有全局锁）：
     * interest = totalBorrows * 年化利率 * dt/年；
     * 借款利息全部转为存款人收益：totalBorrows 与 totalDeposits 同步增加 interest，
     * 再按 depositShares / totalDeposits(息前) 与 borrowShares / totalBorrows(息前) 分摊到各仓位。
     */
    void accrueInterest(Pool pool, LedgerSnapshot snap, long now) {
        long dt = now - pool.getLastAccrualTime();
        if (dt <= 0 || pool.getTotalBorrows().signum() == 0) {
            pool.setLastAccrualTime(Math.max(pool.getLastAccrualTime(), now));
            return;
        }
        BigDecimal u = pool.utilization();
        BigDecimal borrowRate = rateModel.borrowRatePerYear(u);
        BigDecimal years = new BigDecimal(dt).divide(MILLIS_PER_YEAR, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal interest = scale(pool.getTotalBorrows().multiply(borrowRate).multiply(years));
        if (interest.signum() == 0) {
            pool.setLastAccrualTime(now);
            return;
        }

        BigDecimal oldDeposits = pool.getTotalDeposits();
        BigDecimal oldBorrows = pool.getTotalBorrows();
        pool.setTotalBorrows(scale(oldBorrows.add(interest)));
        pool.setTotalDeposits(scale(oldDeposits.add(interest)));

        // 按比例分摊。存款侧以旧总存款为分母，借款侧以旧总借款为分母。
        for (UserAccount account : snap.getAccounts().values()) {
            Position p = account.getPositions().get(pool.getAsset());
            if (p == null) {
                continue;
            }
            if (p.getDepositShares().signum() > 0 && oldDeposits.signum() > 0) {
                BigDecimal shareInterest = scale(interest.multiply(p.getDepositShares()).divide(
                        oldDeposits, MONEY_SCALE, RoundingMode.HALF_UP));
                p.setDepositShares(scale(p.getDepositShares().add(shareInterest)));
            }
            if (p.getBorrowShares().signum() > 0) {
                BigDecimal debtInterest = scale(interest.multiply(p.getBorrowShares()).divide(
                        oldBorrows, MONEY_SCALE, RoundingMode.HALF_UP));
                p.setBorrowShares(scale(p.getBorrowShares().add(debtInterest)));
            }
        }
        pool.setLastAccrualTime(now);
        log.info("[计息] asset={} dt={}ms 利用率={} 年化借款利率={} 区间利息={} totalBorrows={} totalDeposits={}",
                pool.getAsset(), dt, u, borrowRate, interest, pool.getTotalBorrows(), pool.getTotalDeposits());
    }

    // ----------------------------- 查询 / 健康度 -----------------------------

    /**
     * 用户健康度视图：
     * 抵押加权价值 = Σ 存款数量 * 价格 * 抵押因子(CF)；
     * 债务价值     = Σ 借款数量 * 价格；
     * healthFactor = 抵押加权价值 / 债务价值（无债务时为 +∞）。
     * 取价必须为各资产最新且未过期的报价，否则给出明确错误，不用旧价硬算。
     */
    public Map<String, Object> healthView(String userId, long now) {
        validateId(userId, "userId");
        synchronized (store.ledgerLock()) {
            LedgerSnapshot snap = store.load();
            // 查询前先同步利息，使健康度反映当前债务
            for (Pool pool : snap.getPools().values()) {
                accrueInterest(pool, snap, now);
            }
            HealthCheck hc = computeHealth(snap, userId, now);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("userId", userId);
            view.put("collateralValue", hc.collateralValue);
            view.put("adjustedCollateralValue", hc.adjustedCollateralValue);
            view.put("borrowedValue", hc.borrowedValue);
            view.put("healthFactor", hc.healthFactor.signum() < 0 ? "Infinity" : hc.healthFactor);
            view.put("liquidatable",
                    hc.borrowedValue.signum() > 0
                            && hc.healthFactor.compareTo(properties.getLiquidationThreshold()) < 0);
            view.put("positions", hc.lines);
            return view;
        }
    }

    public LedgerSnapshot snapshot() {
        return store.load();
    }

    /** 健康度计算明细（不改状态）。 */
    HealthCheck computeHealth(LedgerSnapshot snap, String userId, long now) {
        UserAccount account = snap.getAccounts().get(userId);
        BigDecimal collateral = BigDecimal.ZERO;
        BigDecimal adjusted = BigDecimal.ZERO;
        BigDecimal debt = BigDecimal.ZERO;
        Map<String, Object> lines = new LinkedHashMap<>();
        if (account != null) {
            for (Map.Entry<String, Position> e : account.getPositions().entrySet()) {
                String asset = e.getKey();
                Position p = e.getValue();
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("deposit", p.getDepositShares());
                line.put("debt", p.getBorrowShares());
                if (p.getDepositShares().signum() > 0 || p.getBorrowShares().signum() > 0) {
                    PriceTick tick = priceService.requireFreshPrice(asset, now);
                    BigDecimal price = tick.getPrice();
                    BigDecimal cf = collateralFactor(asset);
                    BigDecimal depValue = value(p.getDepositShares().multiply(price));
                    BigDecimal debtValue = value(p.getBorrowShares().multiply(price));
                    collateral = collateral.add(depValue);
                    adjusted = adjusted.add(value(depValue.multiply(cf)));
                    debt = debt.add(debtValue);
                    line.put("price", price);
                    line.put("collateralFactor", cf);
                    line.put("depositValue", depValue);
                    line.put("debtValue", debtValue);
                }
                lines.put(asset, line);
            }
        }
        BigDecimal hf = debt.signum() == 0
                ? BigDecimal.valueOf(-1) // 哨兵：无债务
                : adjusted.divide(debt, VALUE_SCALE, RoundingMode.HALF_UP);
        return new HealthCheck(collateral, adjusted, debt, hf, lines);
    }

    record HealthCheck(BigDecimal collateralValue, BigDecimal adjustedCollateralValue,
                       BigDecimal borrowedValue, BigDecimal healthFactor, Map<String, Object> lines) {
    }

    BigDecimal collateralFactor(String asset) {
        return properties.getCollateralFactors().getOrDefault(asset, BigDecimal.ZERO);
    }

    // ----------------------------- 内部工具 -----------------------------

    void commit(LedgerSnapshot snap, String requestId, String operation, long now) {
        OperationRecord record = new OperationRecord();
        record.setRequestId(requestId);
        record.setOperation(operation);
        record.setSuccess(true);
        record.setMessage("OK");
        record.setCreatedAt(now);
        record.setCompletedAt(now);
        snap.getOperations().put(requestId, record);
        snap.setLastOperationTime(now);
        store.persist(snap);
    }

    /** 若 requestId 已登记，返回重放结果；否则返回 null。 */
    OpResult checkReplay(String requestId, String operation) {
        OperationRecord existing = store.load().getOperations().get(requestId);
        if (existing != null) {
            if (!existing.getOperation().equals(operation)) {
                throw new BusinessException(ErrorCode.INVALID_AMOUNT,
                        String.format("requestId=%s 已用于操作 %s，不能复用于 %s",
                                requestId, existing.getOperation(), operation));
            }
            log.info("[幂等] 重复提交直接重放，不重复执行: requestId={} operation={}", requestId, operation);
            return OpResult.replayed(existing.isSuccess(), requestId, operation,
                    existing.getErrorCode(), existing.getMessage());
        }
        return null;
    }

    Pool requirePool(LedgerSnapshot snap, String asset) {
        Pool pool = snap.getPools().get(asset);
        if (pool == null) {
            throw new BusinessException(ErrorCode.POOL_NOT_FOUND, "资金池不存在: asset=" + asset);
        }
        return pool;
    }

    UserAccount requireAccount(LedgerSnapshot snap, String userId) {
        return snap.getAccounts().computeIfAbsent(userId, UserAccount::new);
    }

    UserAccount getAccount(LedgerSnapshot snap, String userId) {
        return snap.getAccounts().get(userId);
    }

    void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, field + " 不能为空");
        }
    }

    void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "金额必须为正数: " + amount);
        }
    }

    static BigDecimal scale(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal value(BigDecimal v) {
        return v.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    }
}
