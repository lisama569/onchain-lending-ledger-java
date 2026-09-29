package com.github.lisama569.onchainlendingledgerjava.ledger;

import com.github.lisama569.onchainlendingledgerjava.model.LedgerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 基于本地 JSON 文件的快照存储。
 * 原子写入：先写 .tmp 再 move(ATOMIC_MOVE/REPLACE)，崩溃时要么旧文件要么新文件，不会读到半个文件。
 */
@Component
public class FileSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(FileSnapshotStore.class);

    private final Path target;
    private final Path tmp;

    public FileSnapshotStore(@Value("${ledger.snapshot-path:data/ledger-snapshot.json}") String path) {
        this.target = Path.of(path);
        this.tmp = Path.of(path + ".tmp");
    }

    @Override
    public synchronized void save(LedgerSnapshot snapshot) throws IOException {
        String json = SnapshotCodec.encode(snapshot);
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("快照已原子落盘: {} ({} 字节)", target, json.length());
    }

    @Override
    public synchronized LedgerSnapshot load() throws IOException {
        if (!Files.exists(target)) {
            log.info("快照文件不存在，从空账本启动: {}", target);
            return null;
        }
        String json = Files.readString(target, StandardCharsets.UTF_8);
        LedgerSnapshot snapshot = SnapshotCodec.decode(json);
        log.info("快照恢复成功: {}, 版本={}, 保存时间={}, 仓位数={}, 已记录请求={}",
                target, snapshot.version(), snapshot.savedAtMillis(),
                snapshot.positions().size(), snapshot.requestLog().size());
        return snapshot;
    }

    Path target() {
        return target;
    }
}
