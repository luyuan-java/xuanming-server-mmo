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

    // ================================================================ 运维作业与栅栏（批次 7.2b，data-ops-spec §8.2）
    // 标签只取固定集合（kind / outcome / source / result 都是代码里的常量），绝不带玩家号 / 作业号 / 区号。

    static final String OPS_JOBS = "xm.data.ops.jobs";
    static final String OPS_JOB_SECONDS = "xm.data.ops.job.seconds";
    static final String OPS_JOBS_RUNNING = "xm.data.ops.jobs.running";
    static final String OPS_PLAYERS = "xm.data.ops.players";
    static final String OPS_CLAIMS = "xm.data.ops.claims";
    static final String OPS_FENCE_HELD = "xm.data.ops.fence.held";
    static final String OPS_FENCE_LOST = "xm.data.ops.fence.lost";
    static final String DIVERGENCE_CHECK = "xm.data.rollback.divergence.check";
    static final String DIVERGENCE_ROWS = "xm.data.rollback.divergence.rows";
    static final String GUILD_CHECK_SECONDS = "xm.data.rollback.guild.check.seconds";
    static final String LOCATION_TOMBSTONES = "xm.data.location.tombstones";

    private final AtomicInteger jobsRunning = new AtomicInteger();
    private final AtomicInteger fenceHeld = new AtomicInteger();
    private volatile boolean opsGaugesRegistered;

    /** 登记两个运维面 gauge（幂等；装配时调用一次，空闲时报 0）。 */
    public void registerOps() {
        if (opsGaugesRegistered) {
            return;
        }
        opsGaugesRegistered = true;
        Gauge.builder(OPS_JOBS_RUNNING, jobsRunning, AtomicInteger::get).description("本实例正在执行的运维作业数")
                .register(registry);
        Gauge.builder(OPS_FENCE_HELD, fenceHeld, AtomicInteger::get)
                .description("本实例此刻以运维身份持有归属的玩家数（空闲时应为 0；持续不为 0 说明作业卡住、玩家进不了游戏）")
                .register(registry);
    }

    /** 作业结局（{@code kind} = 作业种类名，{@code outcome} = 终态名，都是固定集合）。 */
    public void opsJob(String kind, String outcome, long nanos) {
        Counter.builder(OPS_JOBS).description("运维作业的结局").tag("kind", kind).tag("outcome", outcome)
                .register(registry).increment();
        Timer.builder(OPS_JOB_SECONDS).description("运维作业从开始执行到结局的耗时").tag("kind", kind)
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void opsJobRunning(boolean running) {
        if (running) {
            jobsRunning.incrementAndGet();
        } else {
            jobsRunning.decrementAndGet();
        }
    }

    /** 一个玩家在作业里的结局（restored / online / busy / fence_lost / no_snapshot / ...，固定集合）。 */
    public void opsPlayer(String kind, String outcome) {
        Counter.builder(OPS_PLAYERS).description("运维作业逐玩家的结局").tag("kind", kind).tag("outcome", outcome)
                .register(registry).increment();
    }

    /** 一次夺权的结局：claimed / kicked / online / timeout / not_found / error。 */
    public void opsClaim(String outcome) {
        Counter.builder(OPS_CLAIMS).description("运维夺取玩家数据归属的结局").tag("outcome", outcome).register(registry)
                .increment();
    }

    public void fenceHeld(int held) {
        fenceHeld.set(held);
    }

    public void fenceLost(long count) {
        if (count > 0) {
            Counter.builder(OPS_FENCE_LOST).description("运维持有期间续约失败（之后不再对该玩家写）").register(registry)
                    .increment(count);
        }
    }

    /** 资产分歧检查：source = guild / ledger / recall；result 见 data-ops-spec §8.2。 */
    public void divergenceCheck(String source, String result) {
        Counter.builder(DIVERGENCE_CHECK).description("回档的资产分歧检查").tag("source", source).tag("result", result)
                .register(registry).increment();
    }

    public void divergenceRows(String source, boolean accepted, long rows) {
        if (rows > 0) {
            Counter.builder(DIVERGENCE_ROWS).description("回档资产分歧检查列出的分歧行").tag("source", source)
                    .tag("accepted", Boolean.toString(accepted)).register(registry).increment(rows);
        }
    }

    public void guildCheckTime(long nanos) {
        Timer.builder(GUILD_CHECK_SECONDS).description("一次帮会资产检查（含翻页）的耗时").register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 释放归属前写位置墓碑的结局：ok / stale（已有更新的写）/ error。 */
    public void locationTombstone(String result) {
        Counter.builder(LOCATION_TOMBSTONES).description("运维释放归属前写的位置墓碑").tag("result", result)
                .register(registry).increment();
    }
}
