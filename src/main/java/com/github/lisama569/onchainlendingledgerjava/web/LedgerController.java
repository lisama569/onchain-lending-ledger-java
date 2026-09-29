package com.github.lisama569.onchainlendingledgerjava.web;

import com.github.lisama569.onchainlendingledgerjava.service.Clock;
import com.github.lisama569.onchainlendingledgerjava.service.LedgerService;
import com.github.lisama569.onchainlendingledgerjava.service.LiquidationService;
import com.github.lisama569.onchainlendingledgerjava.service.OpResult;
import com.github.lisama569.onchainlendingledgerjava.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 本地借贷协议 REST 入口。所有资金写操作都必须带 requestId（幂等键）；
 * 成功返回 success=true，重复提交返回 success=true 且 replayed=true，业务失败由全局异常处理给出错误码。
 */
@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledgerService;
    private final LiquidationService liquidationService;
    private final PriceService priceService;
    private final Clock clock;

    public LedgerController(LedgerService ledgerService, LiquidationService liquidationService,
                            PriceService priceService, Clock clock) {
        this.ledgerService = ledgerService;
        this.liquidationService = liquidationService;
        this.priceService = priceService;
        this.clock = clock;
    }

    public record AmountRequest(String requestId, String userId, String asset, BigDecimal amount) {}
    public record PriceRequest(String requestId, String asset, Long seq, Long timestamp, BigDecimal price) {}
    public record LiquidateRequest(String requestId, String liquidatorId, String borrowerId,
                                   String debtAsset, String collateralAsset, BigDecimal repayAmount) {}

    @PostMapping("/pools/{asset}/supply")
    public OpResult supply(@PathVariable String asset, @RequestBody AmountRequest req) {
        return ledgerService.supply(req.requestId(), req.userId(), asset, req.amount());
    }

    @PostMapping("/pools/{asset}/withdraw")
    public OpResult withdraw(@PathVariable String asset, @RequestBody AmountRequest req) {
        return ledgerService.withdraw(req.requestId(), req.userId(), req.asset() != null ? req.asset() : asset,
                req.amount());
    }

    @PostMapping("/pools/{asset}/borrow")
    public OpResult borrow(@PathVariable String asset, @RequestBody AmountRequest req) {
        return ledgerService.borrow(req.requestId(), req.userId(), asset, req.amount());
    }

    @PostMapping("/pools/{asset}/repay")
    public OpResult repay(@PathVariable String asset, @RequestBody AmountRequest req) {
        return ledgerService.repay(req.requestId(), req.userId(), asset, req.amount());
    }

    @PostMapping("/prices")
    public OpResult submitPrice(@RequestBody PriceRequest req) {
        if (req.seq() == null || req.timestamp() == null) {
            throw new IllegalArgumentException("price 请求必须包含 seq 与 timestamp");
        }
        priceService.submitTick(req.asset(), req.seq(), req.timestamp(), req.price());
        return OpResult.ok(req.requestId() != null ? req.requestId()
                : "price:" + req.asset() + ":" + req.seq(), "PRICE");
    }

    @GetMapping("/prices")
    public Map<String, Object> prices() {
        return priceService.snapshot(clock.now());
    }

    @PostMapping("/liquidations")
    public OpResult liquidate(@RequestBody LiquidateRequest req) {
        return liquidationService.liquidate(req.requestId(), req.liquidatorId(), req.borrowerId(),
                req.debtAsset(), req.collateralAsset(), req.repayAmount());
    }

    @GetMapping("/users/{userId}/health")
    public Map<String, Object> health(@PathVariable String userId) {
        return ledgerService.healthView(userId, clock.now());
    }

    @GetMapping("/snapshot")
    public Object snapshot() {
        return ledgerService.snapshot();
    }
}
