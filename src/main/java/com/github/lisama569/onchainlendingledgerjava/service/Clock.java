package com.github.lisama569.onchainlendingledgerjava.service;

import org.springframework.stereotype.Component;

/**
 * 逻辑时钟：默认走系统时间，测试可替换/拨快以确定性地验证利息累计与价格过期。
 */
@Component
public class Clock {

    private volatile Long fixedTime;

    public long now() {
        Long fixed = fixedTime;
        return fixed != null ? fixed : System.currentTimeMillis();
    }

    /** 测试用：固定到指定时间。 */
    public void setFixedTime(long millis) {
        this.fixedTime = millis;
    }

    /** 测试用：恢复系统时间。 */
    public void reset() {
        this.fixedTime = null;
    }
}
