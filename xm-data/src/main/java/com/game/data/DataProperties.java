package com.game.data;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * xm-data 的配置（前缀 {@code xm.data}；Kafka 与 topic 代次在 {@code xm.audit}，与生产方同一份口径）。
 *
 * @param transactionLog 资产流水消费者
 * @param playerSnapshot 玩家快照消费者
 * @param dbRetry        落库遇到可恢复故障（库不可达、锁超时……）时的退避：暂停消费、不提交位点、原批次一直重试（宁可积压不丢）
 * @param retention      库内保留期清理
 * @param adminToken     运维接口令牌（环境变量 {@code XM_ADMIN_TOKEN}）；为空时 /admin/** 一律 503
 */
@ConfigurationProperties("xm.data")
public record DataProperties(
        @DefaultValue TransactionLogConsumer transactionLog,
        @DefaultValue PlayerSnapshotConsumer playerSnapshot,
        @DefaultValue DbRetry dbRetry,
        @DefaultValue Retention retention,
        @DefaultValue("") String adminToken) {

    /** 一个消费者的参数（各 topic 缺省值不同，所以每个 topic 一个记录类型）。 */
    public interface ConsumerSettings {

        /** 消费组。 */
        String group();

        /** 一次拉取的上限（同一次拉取的记录在一个库事务里落库）。 */
        int maxPollRecords();

        /** 多行 INSERT 每条语句的行数。 */
        int insertChunk();

        /** 紧凑构造器里字段还没赋值，只能校验参数本身。 */
        static void validate(String group, int maxPollRecords, int insertChunk) {
            if (group == null || group.isBlank() || maxPollRecords < 1 || insertChunk < 1) {
                throw new IllegalArgumentException("xm.data 消费者配置非法");
            }
        }
    }

    public record TransactionLogConsumer(
            @DefaultValue("xm-data-transaction-log") String group,
            @DefaultValue("500") int maxPollRecords,
            @DefaultValue("200") int insertChunk) implements ConsumerSettings {

        public TransactionLogConsumer {
            ConsumerSettings.validate(group, maxPollRecords, insertChunk);
        }
    }

    /** 快照单条可达约 1MB：拉取与分块都小，一条 INSERT 至多约 10MB。 */
    public record PlayerSnapshotConsumer(
            @DefaultValue("xm-data-player-snapshot") String group,
            @DefaultValue("50") int maxPollRecords,
            @DefaultValue("10") int insertChunk) implements ConsumerSettings {

        public PlayerSnapshotConsumer {
            ConsumerSettings.validate(group, maxPollRecords, insertChunk);
        }
    }

    public record DbRetry(@DefaultValue("1s") Duration initial, @DefaultValue("30s") Duration max) {

        public DbRetry {
            if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0) {
                throw new IllegalArgumentException("xm.data.db-retry 配置非法");
            }
        }
    }

    /**
     * @param interval       清理周期
     * @param transactionLog 资产流水保留多久；0 = 永久保留（缺省，同 mmorpg：基线没有任何清理）
     * @param playerSnapshot 玩家快照保留多久；0 = 永久保留（缺省，同 mmorpg）
     * @param batch          每条 DELETE 删多少行（分批，避免长事务与大锁）
     */
    public record Retention(
            @DefaultValue("1h") Duration interval,
            @DefaultValue("0s") Duration transactionLog,
            @DefaultValue("0s") Duration playerSnapshot,
            @DefaultValue("5000") int batch) {

        public Retention {
            if (interval.isNegative() || interval.isZero() || transactionLog.isNegative() || playerSnapshot.isNegative()
                    || batch < 1) {
                throw new IllegalArgumentException("xm.data.retention 配置非法");
            }
        }
    }
}
