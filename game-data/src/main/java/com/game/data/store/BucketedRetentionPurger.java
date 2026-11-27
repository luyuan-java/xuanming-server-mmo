package com.game.data.store;

import com.game.data.consume.AuditTopicScalingPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计表按 {@code (表, 桶 0..15)} 分布式互斥锁（MySQL {@code GET_LOCK}）分工清理过期行（主仓缺口清单第 15 条）：
 * 多个 {@code game-data} 副本各自争抢不同分桶，跳过被其他副本持锁的桶，绝不一起删同一批最老的行。
 */
public final class BucketedRetentionPurger {

    private static final Logger log = LoggerFactory.getLogger(BucketedRetentionPurger.class);

    /** 分桶清理锁抽象（生产用 MySQL {@code GET_LOCK(key, 0)}，单测可注入内存锁）。 */
    public interface ShardLock {
        /**
         * 尝试非阻塞获取指定 {@code lockName} 并在持有期间执行 {@code body}，结束必释放。
         * @return {@code true} = 成功拿锁并执行完成；{@code false} = 已被其他副本持有而跳过。
         */
        boolean withNonBlockingLock(String lockName, Runnable body);

        static ShardLock mysqlGetLock(DataSource dataSource) {
            Objects.requireNonNull(dataSource, "dataSource");
            return (lockName, body) -> {
                try (Connection c = dataSource.getConnection()) {
                    boolean acquired = false;
                    try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                        ps.setString(1, lockName);
                        try (ResultSet rs = ps.executeQuery()) {
                            acquired = rs.next() && rs.getInt(1) == 1;
                        }
                    }
                    if (!acquired) {
                        return false;
                    }
                    try {
                        body.run();
                        return true;
                    } finally {
                        try (PreparedStatement rel = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                            rel.setString(1, lockName);
                            rel.executeQuery();
                        } catch (SQLException ignored) {
                            // 连接归还或关闭时 MySQL 会自动释放会话级 GET_LOCK
                        }
                    }
                } catch (SQLException e) {
                    log.warn("审计分桶清理获取锁 {} 失败，跳过本桶: {}", lockName, e.toString());
                    return false;
                }
            };
        }
    }

    @FunctionalInterface
    public interface ShardBatchDelete {
        int deleteOlderThanInShard(int shard, long before, int limit);
    }

    private final ShardLock lock;
    private final int startOffset;

    public BucketedRetentionPurger(ShardLock lock, int startOffset) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.startOffset = Math.floorMod(startOffset, AuditTopicScalingPolicy.SHARD_COUNT);
    }

    public static String lockKey(String table, int shard) {
        return "audit_purge:" + table + ":" + shard;
    }

    /** 轮询全部 16 个分桶：拿到某桶锁才分批清理该桶，被别处锁住的桶直接跳过。 */
    public long purgeAllShards(String table, long before, int batchLimit, BooleanSupplier keepRunning,
                               Runnable betweenBatches, ShardBatchDelete deleter) {
        long[] total = {0L};
        for (int i = 0; i < AuditTopicScalingPolicy.SHARD_COUNT && keepRunning.getAsBoolean(); i++) {
            int shard = (startOffset + i) % AuditTopicScalingPolicy.SHARD_COUNT;
            lock.withNonBlockingLock(lockKey(table, shard), () -> {
                int deleted;
                do {
                    deleted = deleter.deleteOlderThanInShard(shard, before, batchLimit);
                    total[0] += deleted;
                    if (deleted == batchLimit && betweenBatches != null) {
                        betweenBatches.run();
                    }
                } while (keepRunning.getAsBoolean() && deleted == batchLimit);
            });
        }
        return total[0];
    }
}
