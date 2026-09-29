package com.github.lisama569.onchainlendingledgerjava.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户账户：按资产保存仓位。
 */
public class UserAccount {

    private String userId;
    private Map<String, Position> positions = new HashMap<>();

    public UserAccount() {
    }

    public UserAccount(String userId) {
        this.userId = userId;
    }

    public Position positionOf(String asset) {
        return positions.computeIfAbsent(asset, a -> new Position());
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Map<String, Position> getPositions() { return positions; }
    public void setPositions(Map<String, Position> positions) { this.positions = positions; }
}
