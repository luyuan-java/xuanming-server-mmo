package com.game.guild.asset;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.time.GameDay;
import com.game.guild.asset.AssetOpDecisions.CounterRefund;
import com.game.guild.rules.GuildLimits;
import com.game.guild.store.AssetOpColumns;
import com.game.guild.store.BackgroundTx;
import com.game.guild.store.GuildJdbc;
import com.game.guild.store.GuildStoreException;
import com.game.guild.store.GuildTxOp;
import com.game.guild.store.Invalidation;
import com.game.guild.store.JdbcEconomyStore;
import com.game.guild.store.JdbcGuildStore;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GuildAssetStore} 的 MySQL 实现（语句逐字对齐 mmorpg asset_store.go 与 asset_op_divergence_repo.go；编号见 guild-economy-spec §1.4
 * 的 SQL 目录）。契约与纪律见接口注释；这里补锁序推演。
 *
 * <p><b>锁序</b>（asset_store.go:21-75）：与预留事务同向 G → M → Q → O → C。终结的取锁顺序：DONATE + APPLIED 为 G → M → O；
 * SHOP + 拒绝为 M → Q → O → C；DONATE + 拒绝为 Q → O → C（{@link #lockCounterparty}）。计数行上的全部悲观写者先在同一 (p, stream) 的
 * Q 行上串行（C6）：预留的带上限 upsert 在 AllocateSeq 里已持 Q，退次数 / 退限购在这里最后锁 Q；清理不持 Q，靠「截止至少早 8 天」与
 * upsert 不相遇（C5 补遗），与退款同为主键点锁同序。终结对 op 行的主键 CAS 不需要 Q 守卫：AllocateSeq 的未决行是普通读，预留对 op 表
 * 除自己插入的新行外不持任何锁——这条依赖预留事务是 READ COMMITTED。
 *
 * <p><b>TiDB 附加规则</b>（asset_store.go:35-62，V1 / C5 / G-C2）：凡改到二级索引列或删行的写，一律进显式 RC 事务且先用完整主键点锁
 * （{@link JdbcEconomyStore#LOCK_ASSET_OP}）；领取保留自动提交（只改无索引列，单 key、不会只拿到一部分），不许包进事务。MySQL 下点锁与
 * 随后的主键写锁同一条聚簇记录，锁集不变。Java 首批只上 MySQL，照抄（同 guild-spec §1.5(e)）。
 *
 * <p>与基线的差异：E12（{@link #rescheduleWithoutAnswer}：传输失败不覆盖 last_outcome / last_reason / durable）；E9（终结回调带显式
 * {@link DeliveryOrigin}）；提交后失效经 {@link GuildAssetStore.Listener}（4.4 的失效组件由装配绑定）。线程安全；全部方法阻塞。
 */
public final class JdbcGuildAssetStore implements GuildAssetStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGuildAssetStore.class);

    private static final int PENDING = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE;
    private static final int APPLIED = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE;
    private static final int REJECTED = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED_VALUE;
    private static final int ABORTED = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED_VALUE;
    private static final int APPLIED_PARTIAL = GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL_VALUE;
    private static final int OUTCOME_UNKNOWN = AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE;

    // ---------------------------------------------------------------- O：领取 / 重排 / 终结
    /** O9：ListDue 第一段（新行，attempts &lt; 3）。非加锁读，只回主键。 */
    static final String LIST_DUE_FRESH = "SELECT `op_id` FROM guild_asset_op"
            + " WHERE `status` = ? AND `next_attempt_ms` <= ? AND `lease_until_ms` < ? AND `attempts` < ?"
            + " ORDER BY `next_attempt_ms` ASC, `op_id` ASC LIMIT ?";
    /** O10：ListDue 第二段（老行，只补缺口）。 */
    static final String LIST_DUE_AGED = "SELECT `op_id` FROM guild_asset_op"
            + " WHERE `status` = ? AND `next_attempt_ms` <= ? AND `lease_until_ms` < ? AND `attempts` >= ?"
            + " ORDER BY `next_attempt_ms` ASC, `op_id` ASC LIMIT ?";
    /** O11：领取的主键 CAS（<b>自动提交</b>）。 */
    static final String CLAIM = "UPDATE guild_asset_op SET `lease_until_ms` = ?, `lease_token` = ?, `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `status` = ? AND `lease_until_ms` < ?";
    /** O12：按主键读整行（领取回读、GetOp）。 */
    static final String SELECT_BY_ID = "SELECT " + AssetOpColumns.COLUMNS + " FROM guild_asset_op WHERE `op_id` = ?";
    /**
     * O13：毒行推迟。令牌之外还要带 {@code status = PENDING}：人工终结的 CAS 只看 status、不换令牌，它可能恰好落在领取的 CAS 与这里之间；
     * 只凭令牌会把已终结的行改回「一小时后再来」、抹掉 last_outcome（asset_store.go:198-203）。
     */
    static final String POISON = "UPDATE guild_asset_op"
            + " SET `last_outcome` = ?, `next_attempt_ms` = ?, `lease_until_ms` = 0, `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `lease_token` = ? AND `status` = ?";
    /** O14：重排（带 scene 答复）。0 行 → 租约已丢。 */
    static final String RESCHEDULE = "UPDATE guild_asset_op"
            + " SET `attempts` = `attempts` + 1, `next_attempt_ms` = ?, `lease_until_ms` = 0,"
            + " `durable` = ?, `last_outcome` = ?, `last_reason` = ?, `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `status` = ? AND `lease_token` = ?";
    /** O14'（E12，Java 增项）：重排（没有 scene 答复：传输失败 / 坏流号），不覆盖 durable / last_outcome / last_reason。 */
    static final String RESCHEDULE_WITHOUT_ANSWER = "UPDATE guild_asset_op"
            + " SET `attempts` = `attempts` + 1, `next_attempt_ms` = ?, `lease_until_ms` = 0, `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `status` = ? AND `lease_token` = ?";
    /** O15：终结前在事务外读的<b>不可变列</b>（插入之后没有任何路径改它们）。stream 给 Q 守卫用，不按 kind 反推。 */
    static final String SELECT_IMMUTABLE = "SELECT `player_id`, `guild_id`, `stream`, `kind`, `ref_id`, `ref_count`, `period_key`,"
            + " `contribution_delta`, `funds_delta` FROM guild_asset_op WHERE `op_id` = ?";
    /** O16：终结的 CAS（{@code status = PENDING} 是「一次且仅一次」的最后一道闸）。同写 next_attempt_ms = now。 */
    static final String FINALIZE = "UPDATE guild_asset_op"
            + " SET `status` = ?, `durable` = 1, `last_outcome` = ?, `last_reason` = ?, `reason_tip_id` = ?,"
            + " `lease_until_ms` = 0, `next_attempt_ms` = ?, `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `status` = ?";
    /** O17：人工终结的 CAS（不置 durable、不改 last_outcome；同写 next_attempt_ms = now）。 */
    static final String RESOLVE = "UPDATE guild_asset_op"
            + " SET `status` = ?, `resolved_by` = ?, `resolve_reason` = ?, `lease_until_ms` = 0,"
            + " `next_attempt_ms` = ?, `updated_ms` = ?"
            + " WHERE `op_id` = ? AND `status` = ?";
    /** O18：最老未决行（idx_0 的 status 前缀）。 */
    static final String OLDEST_PENDING = "SELECT MIN(`created_ms`) FROM guild_asset_op WHERE `status` = ? AND `stream` = ?";
    /** O19：assetopfix list。 */
    static final String LIST_STUCK = "SELECT " + AssetOpColumns.COLUMNS + " FROM guild_asset_op"
            + " WHERE `status` = ? AND `created_ms` < ? ORDER BY `created_ms` ASC, `op_id` ASC LIMIT ?";

    // ---------------------------------------------------------------- 对侧账（G / M / Q / C）
    /** G14：终结 DONATE APPLIED 锁帮会行。读不到 = 已解散（orphan）。 */
    static final String LOCK_GUILD_FUNDS = "SELECT funds FROM guild WHERE guild_id = ? FOR UPDATE";
    /** G15：记资金（恰好一行）。 */
    static final String CREDIT_GUILD_FUNDS = "UPDATE guild SET funds = funds + ? WHERE guild_id = ?";
    /** M15：记帮贡（累计与可用同加；恰好一行）。 */
    static final String CREDIT_CONTRIBUTION = "UPDATE guild_member FORCE INDEX (PRIMARY)"
            + " SET contribution_total = contribution_total + ?, contribution_balance = contribution_balance + ?"
            + " WHERE guild_id = ? AND player_id = ?";
    /** M16：兑换退帮贡（恰好一行）。 */
    static final String REFUND_CONTRIBUTION = "UPDATE guild_member FORCE INDEX (PRIMARY)"
            + " SET contribution_balance = contribution_balance + ? WHERE guild_id = ? AND player_id = ?";
    /** Q5：退次数 / 退限购分支的计数行守卫（C6；缺行不算错，只管锁序）。只许完整主键等值 + FOR UPDATE。 */
    static final String LOCK_SEQ_GUARD = "SELECT `next_seq` FROM guild_player_op_seq WHERE `player_id` = ? AND `stream` = ? FOR UPDATE";
    /** C4：退次数 / 退限购（不自检：计数行可能已被清理；IF 兜到 0 防 unsigned 减穿报 1690）。 */
    static final String REFUND_COUNTER = "UPDATE guild_daily_counter SET used_count = IF(used_count >= ?, used_count - ?, 0),"
            + " updated_ms = ? WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ?";

    // ---------------------------------------------------------------- 清理
    /** O20：终态行候选（APPLIED / REJECTED / ABORTED；不含 APPLIED_PARTIAL 与 PENDING）。按 op_id 升序（雪花号随时间单调，E6）。 */
    static final String LIST_CLEANUP_TERMINAL_OPS = "SELECT `op_id` FROM guild_asset_op"
            + " WHERE `status` IN (?, ?, ?) AND `next_attempt_ms` < ? ORDER BY `op_id` ASC LIMIT ?";
    /** O21：终态行带复核点删（之前先 O2 点锁）。 */
    static final String CLEANUP_TERMINAL_OP = "DELETE FROM guild_asset_op"
            + " WHERE `op_id` = ? AND `status` IN (?, ?, ?) AND `next_attempt_ms` < ?";
    /** C5：计数行候选（period_key 范围；日键与周键各走一段）。 */
    static final String LIST_CLEANUP_COUNTERS = "SELECT player_id, counter_kind, ref_id, period_key FROM guild_daily_counter"
            + " WHERE period_key BETWEEN ? AND ? ORDER BY player_id, counter_kind, ref_id, period_key LIMIT ?";
    /** C6：计数行清理点锁（4 列完整主键等值 + FOR UPDATE，与退款的主键点改同序）。 */
    static final String LOCK_CLEANUP_COUNTER = "SELECT period_key FROM guild_daily_counter"
            + " WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ? FOR UPDATE";
    /** C7：计数行带范围复核点删。 */
    static final String CLEANUP_COUNTER = "DELETE FROM guild_daily_counter"
            + " WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ? AND period_key BETWEEN ? AND ?";

    // ---------------------------------------------------------------- O22：回档检查（拼接；只拼结构，值全走占位符）
    static final String APPLIED_OPS_SELECT = "SELECT o.`op_id`, o.`player_id`, o.`guild_id`, o.`stream`, o.`kind`, o.`status`,"
            + " o.`funds_delta`, o.`contribution_delta`, o.`updated_ms` FROM guild_asset_op o";
    /** 仅 zone_id ≠ 0：LEFT JOIN 而不是 INNER JOIN——解散会删 guild 行，INNER JOIN 会丢掉「入账后解散」的行。 */
    static final String APPLIED_OPS_ZONE_JOIN = " LEFT JOIN guild g ON g.guild_id = o.`guild_id`";
    /** 两个状态占位符依次绑 APPLIED、APPLIED_PARTIAL。 */
    static final String APPLIED_OPS_WHERE_HEAD = " WHERE o.`status` IN (?, ?) AND o.`next_attempt_ms` > ? AND o.`player_id` IN (";
    static final String APPLIED_OPS_WHERE_TAIL = ") AND o.`op_id` > ?";
    /** 仅 zone_id ≠ 0：g.guild_id IS NULL = 帮会已解散，照样保留。 */
    static final String APPLIED_OPS_ZONE_FILTER = " AND (g.zone_id = ? OR g.guild_id IS NULL)";
    /** 多取一行（limit + 1）只用来判断还有没有下一页。 */
    static final String APPLIED_OPS_ORDER_LIMIT = " ORDER BY o.`op_id` ASC LIMIT ?";

    /** 计数行的周期截止（含）：period_key 落在 [floor, cutoff] 的行会被删。 */
    public record CounterCutoffs(int dayKey, int weekKey) {
    }

    /** 终结前在事务外读的不可变列（assetOpImmutable）。 */
    record Immutable(long playerId, long guildId, int stream, GuildAssetOpKind kind, int refId, int refCount, int periodKey,
                     long contributionDelta, long fundsDelta) {
    }

    /** 对侧账加锁的结果：行在不在，决定记账还是计 orphan。 */
    private record Locks(boolean guildOk, boolean memberOk) {
    }

    /** 事务体的结局（只在成功返回前交给外层）。 */
    private record Terminated(boolean finalized, Counterparty counterparty) {
        static final Terminated NO = new Terminated(false, Counterparty.NONE);
    }

    /** 一批清理：候选数、删掉的行数、出错时的异常（已删的照样如实返回）。 */
    private record Batch(int candidates, long deleted, RuntimeException error) {
    }

    @FunctionalInterface
    private interface BatchRunner {
        Batch run();
    }

    private record CounterKey(long playerId, int kind, int refId, int periodKey) {
    }

    private final BackgroundTx tx;
    private final Listener listener;

    /**
     * @param tx       后台写事务基座（连接、语句超时上限、重跑钩子都在它里面）
     * @param listener 提交后的钩子（失效 / 指标 / 推送）；assetopfix 传只失效、不推送的实现
     */
    public JdbcGuildAssetStore(BackgroundTx tx, Listener listener) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    // ================================================================ 领取

    @Override
    public List<Long> listDue(long nowMs, int limit, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        Deadline d = within(deadline, GuildLimits.ASSET_STORE_READ_BUDGET_MS);
        return tx.autocommit(d, db -> {
            List<Long> fresh = db.ids(LIST_DUE_FRESH, PENDING, nowMs, nowMs, GuildLimits.ASSET_FRESH_ATTEMPT_LIMIT, limit);
            if (fresh.size() >= limit) {
                return fresh;
            }
            // 两段是两次独立的非锁读，期间 attempts 可能从 2 跳到 3，同一行两段都出现：按 op_id 去重，第一段排前
            List<Long> aged = db.ids(LIST_DUE_AGED, PENDING, nowMs, nowMs, GuildLimits.ASSET_FRESH_ATTEMPT_LIMIT,
                    limit - fresh.size());
            Set<Long> merged = new LinkedHashSet<>(fresh);
            merged.addAll(aged);
            return List.copyOf(merged);
        });
    }

    @Override
    public ClaimResult claim(long opId, long nowMs, long leaseUntilMs, long poisonUntilMs, long token, Deadline deadline) {
        if (token == 0) {
            throw new IllegalArgumentException("claim op " + id(opId) + ": token must be non-zero (0 = no lease)");
        }
        Deadline d = within(deadline, GuildLimits.ASSET_STORE_CLAIM_BUDGET_MS);
        int affected = tx.autocommit(d, db -> db.update(CLAIM, leaseUntilMs, token, nowMs, opId, PENDING, nowMs));
        if (affected != 1) {
            return Lost.INSTANCE;
        }
        GuildAssetOpRow row = tx.autocommit(d, db -> db.one(SELECT_BY_ID, AssetOpColumns::scan, opId));
        if (row == null) {
            // 刚 CAS 成功又查不到：只可能是被并发删了（清理只删终态行），按故障返回
            throw new DependencyException("claim guild_asset_op " + id(opId) + ": row vanished after claim");
        }
        // 回读的行必须仍是「我刚领到的未决行」：CAS 与回读是两条语句，中间可能被人工终结（它的 CAS 只看 status、不换令牌）。
        // 放在解 payload 之前：已终结的坏包行不该再被当成毒行推迟
        if (row.getStatusValue() != PENDING || row.getLeaseToken() != token) {
            return Lost.INSTANCE;
        }
        AssetBundle bundle;
        String decodeError = null;
        if (row.getPayload().isEmpty()) {
            // 空 payload 也算毒行：下发空包 scene 会回包非法，行被 REJECTED 终结并退款——那不是真实的业务结局
            decodeError = "empty payload";
            bundle = null;
        } else {
            try {
                bundle = AssetBundle.parseFrom(row.getPayload());
            } catch (InvalidProtocolBufferException e) {
                decodeError = e.getMessage();
                bundle = null;
            }
        }
        if (bundle == null) {
            markPoison(opId, token, nowMs, poisonUntilMs, d);
            return new Poisoned("op_id=" + id(opId) + ": " + decodeError);
        }
        return new Claimed(new AssetOp(row.getOpId(), row.getPlayerId(), row.getStream(), row.getSeq(),
                row.getStreamEpoch(), row.getOpId(), row.getTxType(), bundle, row.getAttempts(), row.getDeadlineMs(),
                token, row.getLastReason()));
    }

    /**
     * 把毒行推迟到 poisonUntilMs（markPoison，asset_store.go:493-518）：短事务 O2 + O13（带令牌且仍 PENDING）。写失败只记日志：
     * 后果是下一轮再撞一次同一行（仍然跳过），不丢数据。
     */
    void markPoison(long opId, long token, long nowMs, long poisonUntilMs, Deadline deadline) {
        try {
            tx.run(BackgroundTx.Op.POISON, deadline, GuildLimits.BACKGROUND_TX_ATTEMPTS, t -> {
                if (!t.exists(JdbcEconomyStore.LOCK_ASSET_OP, opId)) {
                    return null;
                }
                t.update(POISON, OUTCOME_UNKNOWN, poisonUntilMs, nowMs, opId, token, PENDING);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("[GuildAsset] 推迟毒行失败 op_id={}: {}", id(opId), e.toString());
        }
    }

    // ================================================================ 重排

    @Override
    public RescheduleResult reschedule(AssetOp op, long nextAttemptMs, AssetOpResult answer, long nowMs, Deadline settle) {
        Objects.requireNonNull(answer, "answer（没有 scene 答复时用 rescheduleWithoutAnswer）");
        return doReschedule(op, settle, RESCHEDULE, nextAttemptMs, answer.durable() ? 1 : 0, answer.outcomeNumber(),
                answer.reason(), nowMs, op.opId(), PENDING, op.leaseToken());
    }

    @Override
    public RescheduleResult rescheduleWithoutAnswer(AssetOp op, long nextAttemptMs, long nowMs, Deadline settle) {
        return doReschedule(op, settle, RESCHEDULE_WITHOUT_ANSWER, nextAttemptMs, nowMs, op.opId(), PENDING, op.leaseToken());
    }

    /**
     * 显式 RC 短事务（G-C2）：首句 O2 主键点锁（V1；读不到 = 行已被清理，租约早已不在我手里 → LEASE_LOST），再带令牌 CAS（0 行 → LEASE_LOST）。
     * 截止取 min(1000 ms, settle)。
     */
    private RescheduleResult doReschedule(AssetOp op, Deadline settle, String sql, Object... args) {
        Deadline d = within(settle, GuildLimits.ASSET_STORE_READ_BUDGET_MS);
        return tx.run(BackgroundTx.Op.RESCHEDULE, d, GuildLimits.BACKGROUND_TX_ATTEMPTS, t -> {
            if (!t.exists(JdbcEconomyStore.LOCK_ASSET_OP, op.opId())) {
                return RescheduleResult.LEASE_LOST;
            }
            return t.update(sql, args) == 0 ? RescheduleResult.LEASE_LOST : RescheduleResult.RESCHEDULED;
        });
    }

    // ================================================================ 终结

    @Override
    public FinalizeResult finalizeOp(long opId, AssetOpStatus status, AssetOpResult result, long nowMs,
                                     DeliveryOrigin origin, Deadline settle) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(origin, "origin");
        if (status == null || !status.terminal()) {
            throw new IllegalArgumentException("finalize guild_asset_op " + id(opId) + ": status " + status + " is not terminal");
        }
        // reason_tip_id 只在 REJECTED 时写 scene 原因（玩家看得到的拒绝原因），其余写 0
        int reasonTip = status == AssetOpStatus.REJECTED ? result.reason() : 0;
        return terminate(opId, status, nowMs, origin, settle, BackgroundTx.Op.FINALIZE, FINALIZE,
                AssetOpStatus.toRecord(status).getNumber(), result.outcomeNumber(), result.reason(), reasonTip, nowMs, nowMs,
                opId, PENDING);
    }

    @Override
    public FinalizeResult resolveManually(ManualResolution resolution, long nowMs, Deadline deadline) {
        String invalid = resolution.validate();
        if (invalid != null) {
            throw new IllegalArgumentException("manual resolve guild_asset_op " + id(resolution.opId()) + ": " + invalid);
        }
        return terminate(resolution.opId(), resolution.status(), nowMs, DeliveryOrigin.MANUAL, deadline,
                BackgroundTx.Op.MANUAL_RESOLVE, RESOLVE, AssetOpStatus.toRecord(resolution.status()).getNumber(),
                resolution.operator(), resolution.reason(), nowMs, nowMs, resolution.opId(), PENDING);
    }

    /**
     * Finalize 与 ResolveManually 的共同骨架（terminate，asset_store.go:657-742）；两者只差 CAS 语句。
     * 截止取 min(2000 ms, 调用方截止)；循环路径实际是 settle 的 700 ms。
     */
    private FinalizeResult terminate(long opId, AssetOpStatus status, long nowMs, DeliveryOrigin origin, Deadline deadline,
                                     BackgroundTx.Op op, String casSql, Object... casArgs) {
        Deadline d = within(deadline, GuildLimits.ASSET_STORE_FINALIZE_BUDGET_MS);
        Immutable row = tx.autocommit(d, db -> db.one(SELECT_IMMUTABLE, rs -> new Immutable(GuildJdbc.u64(rs, 1),
                GuildJdbc.u64(rs, 2), GuildJdbc.u32(rs, 3), kindOf(rs.getInt(4)), GuildJdbc.u32(rs, 5), GuildJdbc.u32(rs, 6),
                GuildJdbc.u32(rs, 7), GuildJdbc.u64(rs, 8), GuildJdbc.u64(rs, 9)), opId));
        if (row == null) {
            // 清理只删终态行：这里是数据被人工删了或 op_id 传错。不终结、不报错，让调用方继续下一行
            log.error("[GuildAsset] {}: op_id={} 不存在，跳过", op.label(), id(opId));
            return FinalizeResult.NOT_FINALIZED;
        }
        Terminated done = tx.run(op, d, GuildLimits.BACKGROUND_TX_ATTEMPTS, t -> {
            Locks locks = lockCounterparty(t, row, status);
            // op 行主键点锁（V1）：排在对侧账的 G / M / Q 之后（O 在锁序里靠后）、CAS 之前。读不到 = 已被清理，与 CAS 落空同义
            if (!t.exists(JdbcEconomyStore.LOCK_ASSET_OP, opId)) {
                return Terminated.NO;
            }
            if (t.update(casSql, casArgs) != 1) {
                return Terminated.NO; // 已被别的副本 / 人工终结：不是错误，也不做对侧账
            }
            return new Terminated(true, applyCounterparty(t, row, status, locks, nowMs));
        });
        if (!done.finalized()) {
            return FinalizeResult.NOT_FINALIZED;
        }
        Counterparty c = done.counterparty();
        FinalizedOp finalized = new FinalizedOp(opId, row.playerId(), row.guildId(), row.kind(), status, origin);
        afterCommit(finalized, row, c);
        return new FinalizeResult(true, finalized, c);
    }

    /** 提交后的副作用（只在本次终结时；事务可能被重跑，所以不在体里做）。钩子的异常只记日志：终结已经生效。 */
    private void afterCommit(FinalizedOp op, Immutable row, Counterparty c) {
        try {
            if (c.orphan() != null) {
                listener.orphan(c.orphan());
                log.info("[GuildAsset] 对侧账无处可记 kind={} what={} op_id={} player_id={} guild_id={} funds={} contribution={} status={}",
                        c.orphan().kind(), c.orphan().what(), id(op.opId()), id(row.playerId()), id(row.guildId()),
                        id(row.fundsDelta()), id(row.contributionDelta()), op.status().label());
            }
            if (c.unknownKind()) {
                log.error("[GuildAsset] 未知 kind={}，只终结不做对侧账 op_id={} status={}", row.kind(), id(op.opId()),
                        op.status().label());
            }
            if (c.touched()) {
                listener.invalidate(Invalidation.of(GuildTxOp.ASSET_FINALIZE, row.guildId(), row.playerId()));
            }
            listener.finalized(op);
        } catch (RuntimeException e) {
            log.error("[GuildAsset] 终结 op_id={} 已提交，提交后钩子失败: {}", id(op.opId()), e.toString());
        }
    }

    /**
     * 按锁序先锁对侧账要改的行（lockCounterparty，asset_store.go:779-819），在 op 行点锁与 CAS 之前：
     * <ul>
     *   <li>DONATE + APPLIED：G14 锁帮会行；帮会在才 M1 锁成员行（按 op.guild_id 找——D2：结算一律记给发起时绑定的帮会）；</li>
     *   <li>SHOP + REJECTED / ABORTED：M1 锁成员行（要退帮贡）；</li>
     *   <li>要退次数 / 退限购（{@link AssetOpDecisions#counterRefund} 为真）：最后锁 Q5（p, op 的流）作计数行守卫（C6）。缺行不算错：
     *       守卫只管锁序，在这里拒绝会让这条指令永远停在 PENDING。</li>
     * </ul>
     */
    private static Locks lockCounterparty(GuildJdbc t, Immutable row, AssetOpStatus status) throws SQLException {
        boolean guildOk = false;
        boolean memberOk = false;
        if (row.kind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE && status == AssetOpStatus.APPLIED) {
            guildOk = t.exists(LOCK_GUILD_FUNDS, row.guildId());
            if (guildOk) {
                memberOk = t.exists(JdbcGuildStore.LOCK_MEMBER_ROLE, row.guildId(), row.playerId());
            }
        } else if (row.kind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP
                && (status == AssetOpStatus.REJECTED || status == AssetOpStatus.ABORTED)) {
            memberOk = t.exists(JdbcGuildStore.LOCK_MEMBER_ROLE, row.guildId(), row.playerId());
        }
        if (refundOf(row, status).isPresent()) {
            t.exists(LOCK_SEQ_GUARD, row.playerId(), row.stream());
        }
        return new Locks(guildOk, memberOk);
    }

    /**
     * 对侧账（applyCounterparty，asset_store.go:834-905；guild-economy-spec §2.8 的表）。增量为 0 的列跳过：useAffectedRows 下「加 0」
     * 影响 0 行，会被「恰好一行」的自检误判。
     */
    private static Counterparty applyCounterparty(GuildJdbc t, Immutable row, AssetOpStatus status, Locks locks, long nowMs)
            throws SQLException {
        if (status == AssetOpStatus.APPLIED_PARTIAL) {
            return Counterparty.NONE; // 只 CAS：部分发放转人工补偿，不退次数、不退帮贡
        }
        boolean refunded = status == AssetOpStatus.REJECTED || status == AssetOpStatus.ABORTED;
        boolean touched = false;
        Orphan orphan = null;
        boolean unknownKind = false;
        switch (row.kind()) {
            case GUILD_ASSET_OP_KIND_DONATE -> {
                if (status == AssetOpStatus.APPLIED) {
                    if (!locks.guildOk()) {
                        return new Counterparty(false, Orphan.DONATE_GUILD_GONE, false);
                    }
                    if (row.fundsDelta() != 0) {
                        t.updateExactlyOne("帮会 " + id(row.guildId()) + " 记资金", CREDIT_GUILD_FUNDS, row.fundsDelta(),
                                row.guildId());
                        touched = true;
                    }
                    if (!locks.memberOk()) {
                        return new Counterparty(touched, Orphan.DONATE_MEMBER_GONE, false);
                    }
                    if (row.contributionDelta() != 0) {
                        t.updateExactlyOne("成员 " + id(row.playerId()) + " @ " + id(row.guildId()) + " 记帮贡",
                                CREDIT_CONTRIBUTION, row.contributionDelta(), row.contributionDelta(), row.guildId(),
                                row.playerId());
                        touched = true;
                    }
                }
            }
            case GUILD_ASSET_OP_KIND_SHOP -> {
                if (refunded) {
                    if (!locks.memberOk()) {
                        orphan = Orphan.SHOP_REFUND_MEMBER_GONE;
                    } else if (row.contributionDelta() != 0) {
                        t.updateExactlyOne("成员 " + id(row.playerId()) + " @ " + id(row.guildId()) + " 退帮贡",
                                REFUND_CONTRIBUTION, row.contributionDelta(), row.guildId(), row.playerId());
                        touched = true;
                    }
                }
            }
            case GUILD_ASSET_OP_KIND_ACTIVITY_REWARD -> {
                // 只 CAS：帮贡与资金在入队事务里已记完（4.6）
            }
            default -> unknownKind = true;
        }
        // 退次数 / 退限购：与 lockCounterparty 的 Q 守卫同一个判定，计数行仍是最后一张表（限购属于玩家：成员在不在都退）
        Optional<CounterRefund> refund = refundOf(row, status);
        if (refund.isPresent()) {
            t.update(REFUND_COUNTER, refund.get().count(), refund.get().count(), nowMs, row.playerId(),
                    refund.get().kind().getNumber(), row.refId(), row.periodKey());
        }
        return new Counterparty(touched, orphan, unknownKind);
    }

    private static Optional<CounterRefund> refundOf(Immutable row, AssetOpStatus status) {
        return AssetOpDecisions.counterRefund(row.kind(), row.refCount(), row.periodKey(), status);
    }

    // ================================================================ 读

    @Override
    public OptionalLong oldestPendingCreatedMs(int stream, Deadline deadline) {
        // MIN() 没有未决行时返回一行 NULL
        OptionalLong oldest = tx.autocommit(within(deadline, GuildLimits.ASSET_STORE_READ_BUDGET_MS),
                db -> db.one(OLDEST_PENDING, rs -> {
                    Object v = rs.getObject(1);
                    if (v == null) {
                        return OptionalLong.empty();
                    }
                    return OptionalLong.of(v instanceof BigInteger big ? big.longValue() : ((Number) v).longValue());
                }, PENDING, stream));
        return oldest == null ? OptionalLong.empty() : oldest;
    }

    @Override
    public Optional<GuildAssetOpRow> getOp(long opId, Deadline deadline) {
        return Optional.ofNullable(tx.autocommit(within(deadline, GuildLimits.ASSET_STORE_READ_BUDGET_MS),
                db -> db.one(SELECT_BY_ID, AssetOpColumns::scan, opId)));
    }

    @Override
    public List<GuildAssetOpRow> listStuck(long createdBeforeMs, int limit, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        return tx.autocommit(within(deadline, GuildLimits.ASSET_STORE_READ_BUDGET_MS),
                db -> db.list(LIST_STUCK, AssetOpColumns::scan, PENDING, createdBeforeMs, limit));
    }

    // ================================================================ 清理

    @Override
    public CleanupReport cleanupOnce(long nowMs, Duration terminalRetention, Duration counterRetention) {
        if (terminalRetention.isNegative() || terminalRetention.isZero() || counterRetention.isNegative()
                || counterRetention.isZero()) {
            throw new IllegalArgumentException("guild asset cleanup: retention must be positive (terminal=" + terminalRetention
                    + " counter=" + counterRetention + ")");
        }
        long terminalMs = terminalRetention.toMillis();
        long opCutoffMs = Long.compareUnsigned(nowMs, terminalMs) > 0 ? nowMs - terminalMs : 0;
        CounterCutoffs cutoffs = counterCleanupCutoffs(nowMs, counterRetention.toMillis());

        List<RuntimeException> errors = new ArrayList<>();
        long ops = 0;
        if (opCutoffMs > 0) {
            ops = cleanupInBatches(CleanupTable.ASSET_OP, () -> cleanupTerminalOpsBatch(opCutoffMs), errors);
        }
        long counters = cleanupInBatches(CleanupTable.DAILY_COUNTER,
                () -> cleanupCountersBatch(GuildLimits.DAY_KEY_FLOOR, cutoffs.dayKey()), errors);
        counters += cleanupInBatches(CleanupTable.DAILY_COUNTER,
                () -> cleanupCountersBatch(GuildLimits.WEEK_KEY_FLOOR, cutoffs.weekKey()), errors);
        if (!errors.isEmpty()) {
            DependencyException failure = new DependencyException("guild asset cleanup failed (deleted ops=" + ops
                    + " counters=" + counters + ")", errors.getFirst());
            for (int i = 1; i < errors.size(); i++) {
                failure.addSuppressed(errors.get(i));
            }
            throw failure;
        }
        return new CleanupReport(ops, counters);
    }

    /**
     * 计数行清理的日键 / 周键截止（含；counterCleanupCutoffs，asset_store.go:1042-1048）：截止时刻 = {@code now − max(retention, 8 天)}。
     * 8 天保证上一周期在切周 / 切日后至少再留 24 h，不与在途预留的带上限 upsert 相遇（C5 补遗：相差 7 天的两个时刻必落在相邻两个游戏周）；
     * 夹紧放在这里而不只靠配置校验，绕过配置直接调也删不到上一周期。纯函数。
     */
    public static CounterCutoffs counterCleanupCutoffs(long nowMs, long retentionMs) {
        long cutoffMs = nowMs - Math.max(retentionMs, GuildLimits.MIN_COUNTER_CLEANUP_AGE_MS);
        return new CounterCutoffs(GameDay.dayKey(cutoffMs), GameDay.weekKey(cutoffMs));
    }

    /**
     * 反复跑同一类的批，直到某批候选不足 500、达到 20 批、出错或线程被中断（cleanupInBatches，asset_store.go:1054-1078）。判「还有没有
     * 下一批」看候选数而不是删除数：复核落空的候选下一次不会再读到。
     */
    private long cleanupInBatches(CleanupTable table, BatchRunner batch, List<RuntimeException> errors) {
        long total = 0;
        for (int i = 0; i < GuildLimits.CLEANUP_MAX_BATCHES; i++) {
            if (Thread.currentThread().isInterrupted()) {
                errors.add(new DependencyException("cleanup " + table.label() + " interrupted"));
                return total;
            }
            Batch b = batch.run();
            if (b.deleted() > 0) {
                total += b.deleted();
                notifyDeleted(table, b.deleted());
            }
            if (b.error() != null) {
                errors.add(b.error());
                return total;
            }
            if (b.candidates() < GuildLimits.CLEANUP_BATCH_SIZE) {
                return total;
            }
            try {
                Thread.sleep(GuildLimits.CLEANUP_BATCH_PAUSE_MS); // 批间让路给业务写事务
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(new DependencyException("cleanup " + table.label() + " interrupted", e));
                return total;
            }
        }
        return total;
    }

    private void notifyDeleted(CleanupTable table, long n) {
        try {
            listener.cleanupDeleted(table, n);
        } catch (RuntimeException e) {
            log.error("[GuildAsset] 清理计数钩子失败: {}", e.toString());
        }
    }

    /** 普通读至多 500 个保留期外的三种终态 op_id（升序），逐行短事务点锁 + 点删并复核条件。 */
    private Batch cleanupTerminalOpsBatch(long cutoffMs) {
        List<Long> opIds;
        try {
            opIds = tx.autocommit(Deadline.after(GuildLimits.ASSET_STORE_READ_BUDGET_MS), db -> db.ids(LIST_CLEANUP_TERMINAL_OPS,
                    APPLIED, REJECTED, ABORTED, cutoffMs, GuildLimits.CLEANUP_BATCH_SIZE));
        } catch (RuntimeException e) {
            return new Batch(0, 0, e);
        }
        long deleted = 0;
        for (long opId : opIds) {
            try {
                deleted += cleanupDelete(JdbcEconomyStore.LOCK_ASSET_OP, new Object[] {opId}, CLEANUP_TERMINAL_OP,
                        opId, APPLIED, REJECTED, ABORTED, cutoffMs);
            } catch (RuntimeException e) {
                return new Batch(opIds.size(), deleted, e);
            }
        }
        return new Batch(opIds.size(), deleted, null);
    }

    /**
     * 普通读至多 500 个 period_key 落在 [floor, cutoff] 的计数行主键，逐行短事务点锁 + 点删并复核范围。前提：cutoff 来自
     * {@link #counterCleanupCutoffs}，从不删到仍可能被在途预留 upsert 写的上一周期行。
     */
    private Batch cleanupCountersBatch(int floor, int cutoff) {
        List<CounterKey> keys;
        try {
            keys = tx.autocommit(Deadline.after(GuildLimits.ASSET_STORE_READ_BUDGET_MS), db -> db.list(LIST_CLEANUP_COUNTERS,
                    rs -> new CounterKey(GuildJdbc.u64(rs, 1), rs.getInt(2), GuildJdbc.u32(rs, 3), GuildJdbc.u32(rs, 4)),
                    floor, cutoff, GuildLimits.CLEANUP_BATCH_SIZE));
        } catch (RuntimeException e) {
            return new Batch(0, 0, e);
        }
        long deleted = 0;
        for (CounterKey k : keys) {
            try {
                deleted += cleanupDelete(LOCK_CLEANUP_COUNTER, new Object[] {k.playerId(), k.kind(), k.refId(), k.periodKey()},
                        CLEANUP_COUNTER, k.playerId(), k.kind(), k.refId(), k.periodKey(), floor, cutoff);
            } catch (RuntimeException e) {
                return new Batch(keys.size(), deleted, e);
            }
        }
        return new Batch(keys.size(), deleted, null);
    }

    /**
     * 一个 RC 短事务删一行（execCleanupDelete，asset_store.go:1157-1202）：先完整主键点锁（不带复核条件），再带复核条件的主键点删；
     * 返回删掉的行数（0 或 1）。尝试 1 次（失败的行下一轮再删），2 s 上限。超过 1 行只可能是 WHERE 写坏了，整体回滚并报错。
     */
    private int cleanupDelete(String lockSql, Object[] lockArgs, String deleteSql, Object... args) {
        return tx.run(BackgroundTx.Op.CLEANUP, Deadline.after(GuildLimits.CLEANUP_STMT_BUDGET_MS), 1, t -> {
            if (!t.exists(lockSql, lockArgs)) {
                return 0; // 已被别的副本删掉
            }
            int n = t.update(deleteSql, args);
            if (n > 1) {
                throw new GuildStoreException(GuildStoreException.Kind.ROW_COUNT_MISMATCH,
                        "清理点删删掉了 " + n + " 行（WHERE 已不是完整主键等值）");
            }
            return n;
        });
    }

    // ================================================================ 回档检查

    @Override
    public AppliedOpsPage listAppliedSince(AppliedOpsQuery q, Deadline deadline) {
        String invalid = q.invalidReason();
        if (invalid != null) {
            throw new IllegalArgumentException("list applied guild_asset_op: " + invalid);
        }
        String sql = appliedOpsSql(q.playerIds().size(), q.zoneId() != 0);
        Object[] args = appliedOpsArgs(q);
        List<AppliedOpBrief> rows = tx.autocommit(deadline, db -> db.list(sql, rs -> new AppliedOpBrief(GuildJdbc.u64(rs, 1),
                GuildJdbc.u64(rs, 2), GuildJdbc.u64(rs, 3), GuildJdbc.u32(rs, 4), rs.getInt(5), rs.getInt(6),
                GuildJdbc.u64(rs, 7), GuildJdbc.u64(rs, 8), GuildJdbc.u64(rs, 9)), args));
        // 取回 limit + 1 行 = 还有下一页：丢掉多取的那一行，游标 = 第 limit 行的 op_id
        if (rows.size() > q.limit()) {
            List<AppliedOpBrief> page = rows.subList(0, q.limit());
            return new AppliedOpsPage(page, page.getLast().opId());
        }
        return new AppliedOpsPage(rows, 0);
    }

    /** 按玩家数与是否按 zone 收窄拼出完整语句（appliedOpsSQL）。只拼结构，不拼任何值。 */
    static String appliedOpsSql(int playerCount, boolean byZone) {
        StringBuilder b = new StringBuilder(APPLIED_OPS_SELECT);
        if (byZone) {
            b.append(APPLIED_OPS_ZONE_JOIN);
        }
        b.append(APPLIED_OPS_WHERE_HEAD).append(JdbcGuildStore.placeholders(playerCount)).append(APPLIED_OPS_WHERE_TAIL);
        if (byZone) {
            b.append(APPLIED_OPS_ZONE_FILTER);
        }
        return b.append(APPLIED_OPS_ORDER_LIMIT).toString();
    }

    /** 与 {@link #appliedOpsSql} 的占位符一一对应（appliedOpsArgs）。 */
    static Object[] appliedOpsArgs(AppliedOpsQuery q) {
        List<Object> args = new ArrayList<>(q.playerIds().size() + 6);
        args.add(APPLIED);
        args.add(APPLIED_PARTIAL);
        args.add(q.sinceMs());
        args.addAll(q.playerIds());
        args.add(q.afterOpId());
        if (q.zoneId() != 0) {
            args.add(q.zoneId());
        }
        args.add(q.limit() + 1);
        return args.toArray();
    }

    // ================================================================ 小工具

    /** {@code min(调用方截止的剩余, budget)} 的新截止。 */
    private static Deadline within(Deadline caller, long budgetMs) {
        return Deadline.after(Math.min(caller.remainingMillis(), budgetMs));
    }

    private static GuildAssetOpKind kindOf(int value) {
        GuildAssetOpKind kind = GuildAssetOpKind.forNumber(value);
        return kind == null ? GuildAssetOpKind.UNRECOGNIZED : kind;
    }

    private static String id(long unsigned) {
        return Long.toUnsignedString(unsigned);
    }
}
