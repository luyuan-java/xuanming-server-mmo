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
 * @param ops            运维面（批次 7.2）的通用参数
 * @param recall         批量回收
 * @param rollback       回档（批次 7.2b）
 */
@ConfigurationProperties("xm.data")
public record DataProperties(
        @DefaultValue TransactionLogConsumer transactionLog,
        @DefaultValue PlayerSnapshotConsumer playerSnapshot,
        @DefaultValue DbRetry dbRetry,
        @DefaultValue Retention retention,
        @DefaultValue("") String adminToken,
        @DefaultValue Ops ops,
        @DefaultValue Recall recall,
        @DefaultValue Rollback rollback) {

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
     * 保留期（data-ops-spec §3.7、Q11）。代码缺省全部 0 = 永久保留（同 mmorpg：基线没有任何清理）；生产值由部署给出。
     *
     * <p><b>启动约束</b>（不满足拒绝启动）：流水保留期为 0，或者「快照保留期非 0 且流水保留期 ≥ 快照保留期」——回档窗口内要能用流水解释差异
     * （转移证据），回收窗口也受流水保留期限制。GM / 安全快照（{@code gmSnapshot}）不受这条约束：它们是撤销依据，建议永久保留。
     *
     * @param interval       清理周期
     * @param transactionLog 资产流水保留多久；0 = 永久保留
     * @param playerSnapshot 上下线 / 周期快照（LOGIN / LOGOUT / PERIODIC）保留多久；0 = 永久保留
     * @param gmSnapshot     运维与安全快照（GM_MANUAL / PRE_MAINTENANCE / PRE_ROLLBACK / PRE_GM_EDIT）保留多久；0 = 永久保留（缺省）。
     *                       不在两类里的原因（PRE_TRADE、不认识的值）从不清理
     * @param batch          每条 DELETE 删多少行（分批，避免长事务与大锁）
     */
    public record Retention(
            @DefaultValue("1h") Duration interval,
            @DefaultValue("0s") Duration transactionLog,
            @DefaultValue("0s") Duration playerSnapshot,
            @DefaultValue("0s") Duration gmSnapshot,
            @DefaultValue("5000") int batch) {

        public Retention {
            if (interval.isNegative() || interval.isZero() || transactionLog.isNegative() || playerSnapshot.isNegative()
                    || gmSnapshot.isNegative() || batch < 1) {
                throw new IllegalArgumentException("xm.data.retention 配置非法");
            }
            if (!transactionLog.isZero() && (playerSnapshot.isZero() || transactionLog.compareTo(playerSnapshot) < 0)) {
                throw new IllegalArgumentException("xm.data.retention：资产流水保留期（" + transactionLog
                        + "）必须为 0（永久），或不短于玩家快照保留期且快照保留期非 0（当前 " + playerSnapshot
                        + "）——回档 / 回收窗口内要能用流水解释差异");
            }
        }
    }

    /**
     * 运维面（data-ops-spec §7.2）。
     *
     * @param enabled          改玩家数据的写操作总开关（回档执行、取消作业……）；关闭时这些接口回 503 {@code ops_disabled}，只读接口与 dry-run 照常。
     *                         开启时 {@code XM_DUBBO_SECRET} 必填（帮会检查是 Dubbo 调用方），缺了拒绝启动
     * @param maxWindow        不给玩家、只按原因 / 时间窗的全服流水查询，与不指定玩家的全服回收的时间窗上限（走 idx_txlog_time，防扫全表）
     * @param claimWait        夺权最长等待（kick 时含等 scene 写回释放）；必须长于归属租约 30 s（§4.2：覆盖交出提交后、目标节点进场前无人持有的最坏情况）
     * @param maxPlayersPerJob 一个作业涉及的玩家上限（整区超出回 422 {@code plan_too_large}，不自动拆批）
     * @param jobTimeout       作业时限：写阶段之前超时全部释放、零写入、FAILED {@code job_timeout}；写阶段超时写完当前玩家后停、剩下的记
     *                         {@code not_executed}——一个也没写成 FAILED {@code job_timeout}，写成了一部分 PARTIAL {@code job_timeout}
     * @param minTargetAge     回档目标时刻至少早于现在多久（快照经 Kafka 落库有延迟）
     * @param heartbeat        作业心跳（{@code ops_active.heartbeat_ms}）间隔
     * @param staleAfter       心跳超过它没更新的作业由清扫器改成 INTERRUPTED（每个副本都跑清扫器）
     * @param battleLockWait   回档前查战斗锁（批次 6.3）一次检查全程最多等多久；到点没读完的玩家按在战处理、不写（fail-closed）。
     *                         缺省 5 s：略长于 Redis 单条命令的最坏阻塞（xm.redis 缺省 4.2 s），Redis 故障时先拿到它自己的报错
     */
    public record Ops(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("7d") Duration maxWindow,
            @DefaultValue("35s") Duration claimWait,
            @DefaultValue("10000") int maxPlayersPerJob,
            @DefaultValue("30m") Duration jobTimeout,
            @DefaultValue("5m") Duration minTargetAge,
            @DefaultValue("5s") Duration heartbeat,
            @DefaultValue("60s") Duration staleAfter,
            @DefaultValue("5s") Duration battleLockWait) {

        public Ops {
            if (maxWindow.isNegative() || maxWindow.isZero()) {
                throw new IllegalArgumentException("xm.data.ops.max-window 配置非法");
            }
            if (claimWait.isNegative() || claimWait.isZero() || maxPlayersPerJob < 1 || jobTimeout.isNegative()
                    || jobTimeout.isZero() || minTargetAge.isNegative() || heartbeat.isNegative() || heartbeat.isZero()
                    || staleAfter.compareTo(heartbeat.multipliedBy(3)) < 0 || battleLockWait.isNegative()
                    || battleLockWait.isZero()) {
                throw new IllegalArgumentException("xm.data.ops 配置非法（claim-wait / job-timeout / heartbeat / "
                        + "battle-lock-wait 须为正，max-players-per-job ≥ 1，min-target-age ≥ 0，stale-after ≥ 3 × heartbeat）");
            }
        }
    }

    /** 回档（data-ops-spec §4、§7.2）。 */
    public record Rollback(@DefaultValue Guild guild) {
    }

    /**
     * 帮会资产闸（§4.6.1，逐条对齐基线 {@code config.go:97-129}）。
     *
     * @param settle          帮会检查前的沉降等待（基线常量 30 s，Q6：缺省与基线一致、做成配置）
     * @param recheckDelay    写后复查前的等待
     * @param recheckBudget   写后复查的预算
     * @param clockSkewMargin 检查起点 = 快照内容时刻 − 余量（钳到 ≥ 1）；合法区间 [5 s, 1 h]
     * @param checkBudget     一次检查的预算（缺省 120 s，上限 1 h）
     * @param callTimeout     单次 Dubbo 调用的上限（再受剩余预算约束）
     */
    public record Guild(
            @DefaultValue("30s") Duration settle,
            @DefaultValue("10s") Duration recheckDelay,
            @DefaultValue("120s") Duration recheckBudget,
            @DefaultValue("300s") Duration clockSkewMargin,
            @DefaultValue("120s") Duration checkBudget,
            @DefaultValue("10s") Duration callTimeout) {

        public static final Duration MIN_MARGIN = Duration.ofSeconds(5);
        public static final Duration MAX_MARGIN = Duration.ofHours(1);
        public static final Duration MAX_BUDGET = Duration.ofHours(1);

        public Guild {
            if (settle.isNegative() || recheckDelay.isNegative() || recheckBudget.isNegative() || recheckBudget.isZero()
                    || clockSkewMargin.compareTo(MIN_MARGIN) < 0 || clockSkewMargin.compareTo(MAX_MARGIN) > 0
                    || checkBudget.isNegative() || checkBudget.isZero() || checkBudget.compareTo(MAX_BUDGET) > 0
                    || recheckBudget.compareTo(MAX_BUDGET) > 0 || callTimeout.isNegative() || callTimeout.isZero()) {
                throw new IllegalArgumentException("xm.data.rollback.guild 配置非法（clock-skew-margin ∈ [5s, 1h]，"
                        + "check-budget / recheck-budget ∈ (0, 1h]，settle / recheck-delay ≥ 0，call-timeout > 0）");
            }
        }
    }

    /**
     * @param maxRows 一次回收（含 dry-run）至多匹配多少行源流水；超出判截断，422 {@code result_truncated}、零变更（同基线 recall_logic.go:48）
     */
    public record Recall(@DefaultValue("10000") int maxRows) {

        public Recall {
            if (maxRows < 1) {
                throw new IllegalArgumentException("xm.data.recall.max-rows 配置非法");
            }
        }
    }
}
