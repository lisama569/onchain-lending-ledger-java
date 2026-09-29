package com.github.lisama569.onchainlendingledgerjava.ledger;

import com.github.lisama569.onchainlendingledgerjava.model.LedgerSnapshot;

import java.io.IOException;

/**
 * 快照持久化：原子写入（临时文件 + rename），保证恢复文件不损坏。
 */
public interface SnapshotStore {
    void save(LedgerSnapshot snapshot) throws IOException;

    LedgerSnapshot load() throws IOException;
}
