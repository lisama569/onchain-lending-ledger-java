package com.github.lisama569.onchainlendingledgerjava.ledger;

import com.github.lisama569.onchainlendingledgerjava.common.BusinessException;
import com.github.lisama569.onchainlendingledgerjava.common.ReasonCode;

/**
 * 守恒校验失败：资金/抵押品守恒被破坏（理论上不应发生，是最严重错误）。
 */
public class ConservationException extends BusinessException {
    public ConservationException(String message) {
        super(ReasonCode.STATE_CONSERVATION_BROKEN, message);
    }
}
