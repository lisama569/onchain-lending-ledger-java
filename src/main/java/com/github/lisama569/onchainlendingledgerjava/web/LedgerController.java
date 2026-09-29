package com.github.lisama569.onchainlendingledgerjava.web;

import com.github.lisama569.onchainlendingledgerjava.common.OperationResult;
import com.github.lisama569.onchainlendingledgerjava.domain.PoolState;
import com.github.lisama569.onchainlendingledgerjava.domain.PositionState;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceSnapshot;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceTick;
import com.github.lisama569.onchainlendingledgerjava.ledger.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.model.HealthSnapshot;
import com.github.lisama569.onchainlendingledgerjava.model.LedgerSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 借贷账本 REST 接口。所有写操作必须带 requestId 作为幂等键。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/supply")
    public OperationResult<Map<String, Object>> supply(@RequestBody Dtos.AmountRequest req) {
        return ledger.supply(req.requestId(), req.userId(), req.amount());
    }

    @PostMapping("/withdraw")
    public OperationResult<Map<String, Object>> withdraw(@RequestBody Dtos.AmountRequest req) {
        return ledger.withdraw(req.requestId(), req.userId(), req.amount());
    }

    @PostMapping("/borrow")
    public OperationResult<Map<String, Object>> borrow(@RequestBody Dtos.AmountRequest req) {
        return ledger.borrow(req.requestId(), req.userId(), req.amount());
    }

    @PostMapping("/repay")
    public OperationResult<Map<String, Object>> repay(@RequestBody Dtos.AmountRequest req) {
        return ledger.repay(req.requestId(), req.userId(), req.amount());
    }

    @PostMapping("/collateral/deposit")
    public OperationResult<Map<String, Object>> depositCollateral(@RequestBody Dtos.AmountRequest req) {
        return ledger.depositCollateral(req.requestId(), req.userId(), req.amount());
    }

    @PostMapping("/collateral/withdraw")
    public OperationResult<Map<String, Object>> withdrawCollateral(@RequestBody Dtos.AmountRequest req) {
        return ledger.withdrawCollateral(req.requestId(), req.userId(), req.amount());
    }

    @PostMapping("/liquidate")
    public OperationResult<Map<String, Object>> liquidate(@RequestBody Dtos.LiquidateRequest req) {
        return ledger.liquidate(req.requestId(), req.liquidatorId(), req.borrowerId(), req.repayAmount());
    }

    @PostMapping("/price")
    public OperationResult<PriceSnapshot> submitPrice(@RequestBody Dtos.PriceRequest req) {
        long src = req.sourceTimeMillis() == null ? System.currentTimeMillis() : req.sourceTimeMillis();
        return ledger.submitPrice(new PriceTick(req.seq(), req.price(), src, System.currentTimeMillis()));
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        LedgerSnapshot s = ledger.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pool", poolView(s.pool()));
        m.put("price", s.priceSnapshot());
        m.put("positions", s.positions());
        m.put("recordedRequests", s.requestLog().size());
        return m;
    }

    @GetMapping("/health/{userId}")
    public HealthSnapshot health(@PathVariable String userId) {
        return ledger.health(userId);
    }

    @GetMapping("/health")
    public Object allHealth() {
        return ledger.allHealth();
    }

    private Map<String, Object> poolView(PoolState p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", p.assetId());
        m.put("cash", p.cash().toPlainString());
        m.put("totalSupply", p.totalSupply().toPlainString());
        m.put("totalBorrow", p.totalBorrow().toPlainString());
        m.put("totalReserves", p.totalReserves().toPlainString());
        m.put("utilization", p.utilization().toPlainString());
        m.put("supplyIndex", p.supplyIndex().toPlainString());
        m.put("borrowIndex", p.borrowIndex().toPlainString());
        m.put("lastAccrualTimeMillis", p.lastAccrualTimeMillis());
        return m;
    }
}
