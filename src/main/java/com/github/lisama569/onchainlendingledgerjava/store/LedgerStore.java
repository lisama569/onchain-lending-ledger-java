package com.github.lisama569.onchainlendingledgerjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.onchainlendingledgerjava.config.LedgerProperties;
import com.github.lisama569.onchainlendingledgerjava.domain.LedgerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 账本持久化：每次提交操作后以"临时文件 + 原子 move"写入快照，
 * 启动时加载，保证恢复点之前的已确认操作不丢失、不重复执行。
 *
 * 恢复语义：
 * - 服务先改内存状态，再持久化快照（含本次幂等记录），落盘成功才算操作完成；
 * - 崩溃若发生在落盘前：重启后操作不存在，客户端可用同一 requestId 安全重试；
 * - 崩溃若发生在落盘后：重启后幂等记录仍在，重复提交直接返回原结果，不二次扣款。
 */
@Component
public class LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(LedgerStore.class);

    private final LedgerProperties properties;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    private volatile LedgerSnapshot snapshot;

    public LedgerStore(LedgerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 加载磁盘上的最新快照；不存在时返回空快照（同一实例只加载一次）。 */
    public LedgerSnapshot load() {
        LedgerSnapshot existing = snapshot;
        if (existing != null) {
            return existing;
        }
        synchronized (lock) {
            if (snapshot == null) {
                Path path = Path.of(properties.getSnapshotPath());
                if (Files.exists(path)) {
                    try {
                        snapshot = objectMapper.readValue(path.toFile(), LedgerSnapshot.class);
                        log.info("[恢复] 从 {} 加载账本快照: 资金池={}, 用户={}, 价格帧={}, 已登记操作={}",
                                path.toAbsolutePath(), snapshot.getPools().size(), snapshot.getAccounts().size(),
                                snapshot.getLatestPrices().size(), snapshot.getOperations().size());
                    } catch (IOException e) {
                        throw new UncheckedIOException("账本快照损坏或不可读: " + path, e);
                    }
                } else {
                    snapshot = new LedgerSnapshot();
                    log.info("[恢复] 未发现快照 {}，初始化空账本", path.toAbsolutePath());
                }
            }
            return snapshot;
        }
    }

    /** 原子持久化当前快照：先写临时文件，再原子替换，避免半截文件被恢复读取。 */
    public void persist(LedgerSnapshot current) {
        Path target = Path.of(properties.getSnapshotPath());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), current);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 删除临时文件失败不影响主流程，下次写入会覆盖
            }
            throw new UncheckedIOException("持久化账本快照失败: " + target, e);
        }
    }

    /**
     * 全局账本锁。所有"校验 -> 计息 -> 记账 -> 落盘 -> 幂等登记"必须在同一把锁内完成，
     * 串行化并发请求，杜绝透支与重复计息。
     */
    public Object ledgerLock() {
        return lock;
    }
}
