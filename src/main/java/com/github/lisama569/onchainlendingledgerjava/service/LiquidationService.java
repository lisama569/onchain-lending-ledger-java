package com.github.lisama569.onchainlendingledgerjava.service;

import com.github.lisama569.onchainlendingledgerjava.domain.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.domain.ErrorCode;
import com.github.lisama569.onchainlendingledgerjava.domain.LedgerSnapshot;
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

/**
 * 清算服务。
 *
 * 清算条件（全部满足）：
 * 1. 借款人有债务，且 healthFactor < 清算阈值（默认 1.0），即抵押加权价值已覆盖不了债务；
 * 2. 涉及资产报价新鲜（过期价绝不允许清算，防误清算）；
 * 3. 本次偿还价值不超过借款人总债务价值的 maxCloseFactor（默认 50%），即只允许部分清算；
 * 4. 清算人在债务资产池中有足额存款作为偿付来源；
 * 5. 借款人抵押品余额足以支付含清算奖励的没收数量。
 *
 * 资金流（全部为账本内部划转，故三方守恒可精确验证）：
 * - 还债：清算人 debtAsset 存款 -= repayAmount（totalDeposits 减），
 *   借款人 debtAsset 借款 -= repayAmount（totalBorrows 减），现金不变；
 * - 没收：借款人 collateralAsset 存款 -= seized，清算人 collateralAsset 存款 += seized；
 * - seized = repayAmount * debtPrice / collateralPrice * (1 + liquidationBonus)；
 * 对每个资产恒有 cash + totalBorrows = totalDeposits。
 */
@Service
public class LiquidationService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationService.class);

    private final LedgerStore store;
    private final LedgerService ledgerService;
    private final PriceService priceService;
    private final com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties properties;
    private final Clock clock;

    public LiquidationService(LedgerStore store, LedgerService ledgerService, PriceService priceService,
                              com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties properties,
                              Clock clock) {
        this.store = store;
        this.ledgerService = ledgerService;
        this.priceService = priceService;
        this.properties = properties;
        this.clock = clock;
    }

    public OpResult liquidate(String requestId, String liquidatorId, String borrowerId,
                              String debtAsset, String collateralAsset, BigDecimal repayAmount) {
        ledgerService.validateId(requestId, "requestId");
        ledgerService.validateId(liquidatorId, "liquidatorId");
        ledgerService.validateId(borrowerId, "borrowerId");
        ledgerService.validateId(debtAsset, "debtAsset");
        ledgerService.validateId(collateralAsset, "collateralAsset");
        ledgerService.validateAmount(repayAmount);
        if (debtAsset.equals(collateralAsset)) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT,
                    "债务资产与抵押资产不能相同: " + debtAsset);
        }
        if (liquidatorId.equals(borrowerId)) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "清算人不能清算自己的仓位");
        }

        long now = clock.now();
        synchronized (store.ledgerLock()) {
            OpResult replay = ledgerService.checkReplay(requestId, "LIQUIDATE");
            if (replay != null) {
                return replay;
            }
            LedgerSnapshot snap = store.load();
            // 清算前先同步全部资产利息，保证健康度与债务金额是当前值
            for (Pool pool : snap.getPools().values()) {
                ledgerService.accrueInterest(pool, snap, now);
            }

            // 1. 健康度判定（内部对各资产要求新鲜报价）
            LedgerService.HealthCheck health = ledgerService.computeHealth(snap, borrowerId, now);
            if (health.borrowedValue().signum() == 0) {
                throw new BusinessException(ErrorCode.POSITION_NOT_BORROWED,
                        "借款人无债务，无需清算: borrower=" + borrowerId);
            }
            if (health.healthFactor().compareTo(properties.getLiquidationThreshold()) >= 0) {
                throw new BusinessException(ErrorCode.POSITION_HEALTHY,
                        String.format("仓位健康，禁止清算: borrower=%s, HF=%s, 阈值=%s",
                                borrowerId, health.healthFactor(), properties.getLiquidationThreshold()));
            }

            // 2. 金额上限：单次最多偿还总债务价值的 maxCloseFactor
            PriceTick debtTick = priceService.requireFreshPrice(debtAsset, now);
            PriceTick collTick = priceService.requireFreshPrice(collateralAsset, now);
            BigDecimal repayValue = LedgerService.value(repayAmount.multiply(debtTick.getPrice()));
            BigDecimal maxRepayValue = LedgerService.value(
                    health.borrowedValue().multiply(properties.getMaxCloseFactor()));
            if (repayValue.compareTo(maxRepayValue) > 0) {
                throw new BusinessException(ErrorCode.LIQUIDATION_TOO_LARGE,
                        String.format("单次清算超出比例上限: 偿还价值=%s, 上限=%s（总债务价值=%s 的 %s）",
                                repayValue, maxRepayValue, health.borrowedValue(),
                                properties.getMaxCloseFactor()));
            }

            UserAccount borrower = snap.getAccounts().get(borrowerId);
            Position borrowerDebtPos = borrower.getPositions().get(debtAsset);
            BigDecimal debtBalance = borrowerDebtPos == null
                    ? BigDecimal.ZERO : borrowerDebtPos.getBorrowShares();
            if (repayAmount.compareTo(debtBalance) > 0) {
                throw new BusinessException(ErrorCode.LIQUIDATION_TOO_LARGE,
                        String.format("偿还数量超过该资产债务: 请求=%s, 债务=%s", repayAmount, debtBalance));
            }

            // 3. 清算人偿付能力（其在债务资产池中的存款即偿付来源）
            UserAccount liquidator = snap.getAccounts().computeIfAbsent(liquidatorId, UserAccount::new);
            Position liqDebtPos = liquidator.positionOf(debtAsset);
            if (repayAmount.compareTo(liqDebtPos.getDepositShares()) > 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_COLLATERAL,
                        String.format("清算人偿付资金不足: liquidator=%s asset=%s, 需要=%s, 存款=%s",
                                liquidatorId, debtAsset, repayAmount, liqDebtPos.getDepositShares()));
            }

            // 4. 计算应没收抵押品（含清算奖励）
            BigDecimal bonus = BigDecimal.ONE.add(properties.getLiquidationBonus());
            BigDecimal seized = LedgerService.scale(
                    repayAmount.multiply(debtTick.getPrice())
                            .divide(collTick.getPrice(), LedgerService.MONEY_SCALE, RoundingMode.HALF_UP)
                            .multiply(bonus));
            Position borrowerCollPos = borrower.getPositions().get(collateralAsset);
            BigDecimal collateralBalance = borrowerCollPos == null
                    ? BigDecimal.ZERO : borrowerCollPos.getDepositShares();
            if (seized.compareTo(collateralBalance) > 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_COLLATERAL,
                        String.format("借款人抵押品不足以覆盖含奖励的没收量: 需要=%s, 余额=%s",
                                seized, collateralBalance));
            }

            // 5. 执行划转
            Pool debtPool = ledgerService.requirePool(snap, debtAsset);
            Pool collPool = ledgerService.requirePool(snap, collateralAsset);

            liqDebtPos.setDepositShares(LedgerService.scale(liqDebtPos.getDepositShares().subtract(repayAmount)));
            debtPool.setTotalDeposits(LedgerService.scale(debtPool.getTotalDeposits().subtract(repayAmount)));
            borrowerDebtPos.setBorrowShares(LedgerService.scale(debtBalance.subtract(repayAmount)));
            debtPool.setTotalBorrows(LedgerService.scale(debtPool.getTotalBorrows().subtract(repayAmount)));

            borrowerCollPos.setDepositShares(LedgerService.scale(collateralBalance.subtract(seized)));
            Position liqCollPos = liquidator.positionOf(collateralAsset);
            liqCollPos.setDepositShares(LedgerService.scale(liqCollPos.getDepositShares().add(seized)));

            log.info("[清算] requestId={} liquidator={} borrower={} debtAsset={} repay={} collAsset={} seized={} bonus={} | 清算前HF={} 依据: 抵押加权={} 债务价值={}",
                    requestId, liquidatorId, borrowerId, debtAsset, repayAmount, collateralAsset, seized,
                    properties.getLiquidationBonus(), health.healthFactor(),
                    health.adjustedCollateralValue(), health.borrowedValue());

            ledgerService.commit(snap, requestId, "LIQUIDATE", now);
            return OpResult.ok(requestId, "LIQUIDATE");
        }
    }

    public LedgerSnapshot snapshot() {
        return store.load();
    }
}
