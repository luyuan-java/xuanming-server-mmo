package com.game.data.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * xm-data 的低基数指标（标签只有消费者名 / 结局 / 表名 / 操作，绝不带玩家号）。
 */
public final class DataMetrics {

    static final String CONSUMER_UP = "xm.data.kafka.consumer.up";
    static final String RECORDS = "xm.data.kafka.records";
    static final String CONSUMER_LAG = "xm.data.kafka.consumer.lag";
    static final String DB_INSERT = "xm.data.db.insert";
    static final String RETENTION_DELETED = "xm.data.retention.deleted";
    static final String ADMIN_REQUESTS = "xm.data.admin.requests";
    static final String SNAPSHOT_ADMIN = "xm.data.snapshot.admin";

    /** 一条 Kafka 记录的结局（{@code xm.data.kafka.records{outcome}}），每条恰好计一次。 */
    public enum Outcome {
        /** 新插入。 */
        INSERTED,
        /** 已存在（重放），什么都没改。 */
        DUPLICATE,
        /** 载荷解不出来：跳过（位点照常提交）。 */
        DECODE_ERROR,
        /** 字段非法（号为 0、种类未指定）：跳过。 */
        INVALID,
        /** 数据库拒绝这一行（数据错误）：写毒丸日志后跳过。 */
        REJECTED
    }

    private final MeterRegistry registry;
    private final Map<String, Map<Outcome, Counter>> records = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> up = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lag = new ConcurrentHashMap<>();
    private final Map<String, Counter> dbErrors = new ConcurrentHashMap<>();

    public DataMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 登记一个消费者（启动前调用一次：up 先报 0）。 */
    public void registerConsumer(String consumer) {
        EnumMap<Outcome, Counter> byOutcome = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            byOutcome.put(outcome, Counter.builder(RECORDS)
                    .description("消费到的审计记录的结局")
                    .tag("consumer", consumer)
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        records.put(consumer, byOutcome);
        AtomicInteger upValue = up.computeIfAbsent(consumer, c -> new AtomicInteger());
        Gauge.builder(CONSUMER_UP, upValue, AtomicInteger::get)
                .description("消费者是否在跑（topic 核对通过、轮询线程活着）")
                .tag("consumer", consumer)
                .register(registry);
        AtomicLong lagValue = lag.computeIfAbsent(consumer, c -> new AtomicLong());
        Gauge.builder(CONSUMER_LAG, lagValue, AtomicLong::get)
                .description("分配到的分区上还没落库的记录数之和（每次拉取后更新；落库重试暂停期间每轮退避后也刷新）")
                .tag("consumer", consumer)
                .register(registry);
        dbErrors.put(consumer, Counter.builder(DB_INSERT + ".errors")
                .description("落库失败的尝试（可恢复故障会退避重试，不提交位点）")
                .tag("consumer", consumer)
                .register(registry));
    }

    public void record(String consumer, Outcome outcome, long count) {
        if (count > 0) {
            records.get(consumer).get(outcome).increment(count);
        }
    }

    public void up(String consumer, boolean running) {
        up.get(consumer).set(running ? 1 : 0);
    }

    public void lag(String consumer, long value) {
        lag.get(consumer).set(value);
    }

    public void dbError(String consumer) {
        dbErrors.get(consumer).increment();
    }

    public void insertTime(String consumer, long nanos) {
        Timer.builder(DB_INSERT).description("一次拉取的记录在一个事务里落库的耗时").tag("consumer", consumer)
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void retentionDeleted(String table, long rows) {
        Counter.builder(RETENTION_DELETED).description("保留期清理删掉的行").tag("table", table).register(registry)
                .increment(rows);
    }

    public void adminRequest(String op, String result) {
        Counter.builder(ADMIN_REQUESTS).description("运维接口请求").tag("op", op).tag("result", result)
                .register(registry).increment();
    }

    /**
     * 运维直写快照的结局（{@code xm_data_snapshot_admin_total}）。标签取值有界：cause 只有手工快照接受的原因名，
     * result 是固定集合（ok / replayed / player_not_found / id_unavailable / idempotency_conflict / db_error）。
     */
    public void snapshotAdmin(String cause, String result) {
        Counter.builder(SNAPSHOT_ADMIN).description("运维直写快照的结局").tag("cause", cause).tag("result", result)
                .register(registry).increment();
    }
}
