package com.game.inbox.store;

import static com.game.inbox.store.InboxSql.ITEM_TABLE;
import static com.game.inbox.store.InboxSql.PENDING;
import static com.game.inbox.store.InboxSql.STREAM_PK;
import static com.game.inbox.store.InboxSql.STREAM_TABLE;
import static com.game.inbox.store.InboxSql.TERMINAL_STATES;
import static com.game.inbox.store.InboxSql.u32;
import static com.game.inbox.store.InboxSql.u64;

import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.metrics.InboxMetrics.ScanGauges;
import com.game.inbox.notify.Notifier;
import com.game.inbox.notify.NotifyResult;
import com.game.inbox.rules.InboxShards;
import com.game.inbox.rules.LedgerProofs.FinalizedRef;
import com.game.proto.player_inbox.PlayerInboxDomain;
import com.game.proto.player_inbox.PlayerInboxFinalizer;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台循环用到的存储操作（mmorpg {@code store_maintenance.go}；设计 §6.3、§9.1、§9.3、§9.4）。读不加领域锁，写先取路由/P短事务，不持流行锁：
 * 终态原收据永久保留；提醒清零 / 推迟都是 next_seq CAS，领导者切换期间重叠执行也无害。线程安全。
 */
public final class InboxMaintenanceStore {

    private static final Logger log = LoggerFactory.getLogger(InboxMaintenanceStore.class);

    /** 后台循环里单条语句的预算。 */
    public static final Duration STATEMENT_BUDGET = Duration.ofSeconds(2);
    /** 补发里单次提醒（读位置、调 scene）的预算（mmorpg {@code NotifyTimeout × 5}）。 */
    public static final Duration RESEND_NOTIFY_TIMEOUT = InboxStore.NOTIFY_TIMEOUT.multipliedBy(5);

    /**
     * 一条到期待补发的提醒：读出当时的 next_seq 作 CAS 令牌，流行的 updated_ms 作退避起算点（不晚于这条提醒真正开始悬着的时刻：
     * notify_due_ms 只由入箱置成 now，同一条语句把 updated_ms 也写成同一个 now；之后的写只会把 updated_ms 往后推）。
     */
    public record NotifyDue(long playerId, PlayerInboxDomain domain, long nextSeq, long pendingSinceMs) {
    }

    private final AuthorityJdbc jdbc;
    private final Notifier notifier;
    private final InboxMetrics metrics;
    private final LongSupplier nowMs;

    public InboxMaintenanceStore(AuthorityJdbc jdbc, Notifier notifier, InboxMetrics metrics, LongSupplier nowMs) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
    }

    private static Deadline statement() {
        return Deadline.after(STATEMENT_BUDGET.toMillis());
    }

    // ================================================================ 清理（设计 §9.1）

    /** 旧运维调用保留为显式不删除：终态原唯一收据永久阻止跨owner重放，不受保留期或领导权TTL影响。 */
    public int sweepTerminal(long cutoffMs, int budget, java.util.function.BooleanSupplier stop) throws SQLException {
        metrics.sweepDeleted(0);
        return 0;
    }

    // ================================================================ 提醒补发（设计 §6.3）

    /** 一个分片里到期的提醒：notify_due_ms ∈ [1, dueBeforeMs]，最多 limit 条（走 (shard, notify_due_ms) 索引）。 */
    public List<NotifyDue> dueNotifications(int shard, long dueBeforeMs, int limit) throws SQLException {
        List<long[]> rows = jdbc.read(statement(), s -> s.list("SELECT `player_id`, `domain`, `next_seq`, `updated_ms` FROM " + STREAM_TABLE
                        + " WHERE `shard` = ? AND `notify_due_ms` BETWEEN 1 AND ? LIMIT ?",
                row -> new long[] {InboxSql.u64(row, "player_id"), InboxSql.u32(row, "domain"), InboxSql.u64(row, "next_seq"),
                        InboxSql.u64(row, "updated_ms")}, shard, u64(dueBeforeMs), limit));
        List<NotifyDue> out = new ArrayList<>(rows.size());
        for (long[] row : rows) {
            InboxSql.checkRowShard(shard, row[0]);
            PlayerInboxDomain domain = PlayerInboxDomain.forNumber((int) row[1]);
            out.add(new NotifyDue(row[0], domain == null ? PlayerInboxDomain.UNRECOGNIZED : domain, row[2], row[3]));
        }
        return out;
    }

    /**
     * 给一条到期提醒补发一次（评审 L6）：
     * <ul>
     *   <li>落定（送达 / 离线 / 等待落点 / 送不到）：按读出时的 next_seq 做 CAS 置 0；</li>
     *   <li>NODE_MISSING 且已悬满 1 小时：同样 CAS 置 0，打 ERROR；</li>
     *   <li>其余没落定：在同一个 next_seq CAS 里把 notify_due_ms 推到 now + 退避（30s 起、按已悬时长翻倍、封顶 30min），到点之前不再被扫到。</li>
     * </ul>
     * CAS 失败都无害：这期间又有新的入箱，notify_due_ms 已被它写成 now，补发照常尽快处理。
     */
    public NotifyResult resendNotify(NotifyDue due) {
        NotifyResult result = notifier.notify(due.playerId(), due.domain(), RESEND_NOTIFY_TIMEOUT);
        try {
            if (result.settled()) {
                InboxStore.clearNotifyDue(statement(), jdbc, due.playerId(), due.domain(), due.nextSeq());
                return result;
            }
            long now = nowMs.getAsLong();
            if (NotifyResult.givesUp(result, now, due.pendingSinceMs())) {
                log.error("[PlayerInbox] 提醒补发在节点目录里一直找不到目标节点满 {} 分钟，落定 player={} domain={}（位置记录停在一个已不在的节点上；交给落地拉取）",
                        (now - due.pendingSinceMs()) / 60_000, Long.toUnsignedString(due.playerId()), due.domain().getNumber());
                InboxStore.clearNotifyDue(statement(), jdbc, due.playerId(), due.domain(), due.nextSeq());
                return result;
            }
            long dueMs = NotifyResult.retryDueMs(now, due.pendingSinceMs());
            deferNotifyDue(due, dueMs);
        } catch (SQLException e) {
            // 清零 / 推迟失败只是这一行下一轮还会被扫到（退避前的行为），不丢提醒
            log.warn("[PlayerInbox] 提醒补发之后改 notify_due 失败 player={} domain={} result={}: {}（下一轮重试）",
                    Long.toUnsignedString(due.playerId()), due.domain().getNumber(), result.label(), e.toString());
        }
        return result;
    }

    /** 把一条没落定的提醒推迟到 dueMs，与清零同一个 next_seq CAS 令牌；<b>不碰 updated_ms</b>（它是退避的起算点）。 */
    boolean deferNotifyDue(NotifyDue due, long dueMs) throws SQLException {
        int shard = InboxShards.rowShard(due.playerId());
        Deadline budget = statement();
        return jdbc.tx(budget, s -> {
            InboxSql.lockPlayer(s, budget, due.playerId());
            return s.update("UPDATE " + STREAM_TABLE + " SET `notify_due_ms` = ? WHERE " + STREAM_PK
                        + " AND `next_seq` = ? AND `notify_due_ms` <> 0", u64(dueMs), shard, u64(due.playerId()), u32(due.domain().getNumber()),
                u64(due.nextSeq()));
        }) == 1;
    }

    // ================================================================ 扫描型 gauge（设计 §9.4）

    /**
     * 扫一遍全部分片，算出扫描型 gauge（只由领导者调用）。任何一个分片读失败整轮作废（抛出、不更新 gauge），不拿半套分片的数刷看板。
     *
     * @param notifyOverdueBeforeMs  notify_due_ms 非 0、且流行最后一次写入不晚于它的流计入 notify.backlog（悬了 5 分钟以上；按 updated_ms 而不按
     *                               notify_due_ms 算：没落定的提醒 notify_due_ms 被推到了未来）
     * @param deliveredStaleBeforeMs 已投递未确认、最后一次投递早于它的行算陈旧，只进 delivered.unacked.stale，不进告警口径的三项（评审 M4）
     */
    public ScanGauges scanGauges(long nowMs, long notifyOverdueBeforeMs, long deliveredStaleBeforeMs, java.util.function.BooleanSupplier stop)
            throws SQLException {
        long openItems = 0;
        long deliveredUnacked = 0;
        long deliveredStale = 0;
        long maxDelivery = 0;
        long oldestCreated = 0;
        long oldestDelivered = 0;
        long backlog = 0;
        for (int shard = 0; shard < InboxShards.SHARD_COUNT; shard++) {
            if (stop.getAsBoolean()) {
                throw new SQLException("扫描型 gauge 被打断（领导权丢失 / 关停）");
            }
            final int s0 = shard;
            long[] agg = jdbc.read(statement(), s -> s.one("SELECT COUNT(*) AS open_items,"
                            + " COALESCE(SUM(CASE WHEN `delivery_count` > 0 AND `last_delivered_ms` >= ? THEN 1 ELSE 0 END), 0) AS recent,"
                            + " COALESCE(SUM(CASE WHEN `delivery_count` > 0 AND `last_delivered_ms` < ? THEN 1 ELSE 0 END), 0) AS stale,"
                            + " COALESCE(MAX(CASE WHEN `delivery_count` > 0 AND `last_delivered_ms` >= ? THEN `delivery_count` END), 0) AS max_delivery,"
                            + " COALESCE(MIN(`created_ms`), 0) AS min_created,"
                            + " COALESCE(MIN(CASE WHEN `delivery_count` > 0 AND `last_delivered_ms` >= ? THEN `last_delivered_ms` END), 0) AS min_delivered"
                            + " FROM " + ITEM_TABLE + " WHERE `shard` = ? AND `state` = ?",
                    row -> new long[] {row.getLong("open_items"), row.getLong("recent"), row.getLong("stale"), row.getLong("max_delivery"),
                            InboxSql.u64(row, "min_created"), InboxSql.u64(row, "min_delivered")},
                    u64(deliveredStaleBeforeMs), u64(deliveredStaleBeforeMs), u64(deliveredStaleBeforeMs), u64(deliveredStaleBeforeMs), s0,
                    PENDING));
            long shardBacklog = jdbc.read(statement(), s -> s.count("SELECT COUNT(*) FROM " + STREAM_TABLE
                    + " WHERE `shard` = ? AND `notify_due_ms` >= 1 AND `updated_ms` <= ?", s0, u64(notifyOverdueBeforeMs)));
            openItems += agg[0];
            deliveredUnacked += agg[1];
            deliveredStale += agg[2];
            maxDelivery = Math.max(maxDelivery, agg[3]);
            oldestCreated = minNonZero(oldestCreated, agg[4]);
            oldestDelivered = minNonZero(oldestDelivered, agg[5]);
            backlog += shardBacklog;
        }
        return new ScanGauges(openItems, ageSeconds(nowMs, oldestCreated), deliveredUnacked, ageSeconds(nowMs, oldestDelivered),
                deliveredStale, maxDelivery, backlog);
    }

    static long minNonZero(long a, long b) {
        if (a == 0) {
            return b;
        }
        if (b == 0) {
            return a;
        }
        return Long.compareUnsigned(a, b) <= 0 ? a : b;
    }

    /** sinceMs 距 nowMs 的秒数；sinceMs 为 0（没有这类行）或不早于 nowMs（时钟回拨）时为 0。 */
    static double ageSeconds(long nowMs, long sinceMs) {
        if (sinceMs == 0 || Long.compareUnsigned(sinceMs, nowMs) >= 0) {
            return 0;
        }
        return (nowMs - sinceMs) / 1000.0;
    }

    // ================================================================ 对账读（设计 §9.3）

    /**
     * 一个分片里 updated_ms ≥ sinceMs、由消费方终结的终态行的一页，最多 limit 条。按 (updated_ms, player_id, domain, seq) <b>降序</b>、
     * 键集翻页：before 为 null 从最新的一行读起，否则只读严格排在 before 之后（更旧）的行。后三项即主键，同一分片内全序唯一，翻页不重不漏；
     * 终态行的 updated_ms 终结之后不再变。降序的理由：单片读满上限时被截掉的是最老的那部分（它们在更早的轮次里已作为「最新」被核对过）。
     * 必须有 ORDER BY（评审 M5：没有它 LIMIT 截下的是索引上 state 最小的那一段）。游标条件写成展开式（MySQL 对行比较不做范围优化）。
     */
    public List<FinalizedRef> recentlyFinalized(int shard, long sinceMs, FinalizedRef before, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT `player_id`, `domain`, `seq`, `state`, `request_id`, `msg_type`, `outcome_reason`,"
                + " `finalized_by`, `updated_ms` FROM " + ITEM_TABLE + " WHERE `shard` = ? AND `state` IN " + TERMINAL_STATES
                + " AND `updated_ms` >= ? AND `finalized_by` = ?");
        List<Object> args = new ArrayList<>(List.of(shard, u64(sinceMs), PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_CONSUMER_VALUE));
        if (before != null) {
            sql.append(" AND (`updated_ms` < ? OR (`updated_ms` = ? AND (`player_id` < ? OR (`player_id` = ? AND"
                    + " (`domain` < ? OR (`domain` = ? AND `seq` < ?))))))");
            args.addAll(List.of(u64(before.updatedMs()), u64(before.updatedMs()), u64(before.playerId()), u64(before.playerId()),
                    u32(before.domain()), u32(before.domain()), u64(before.seq())));
        }
        sql.append(" ORDER BY `updated_ms` DESC, `player_id` DESC, `domain` DESC, `seq` DESC LIMIT ?");
        args.add(limit);
        List<FinalizedRef> refs = jdbc.read(statement(), s -> s.list(sql.toString(), row -> new FinalizedRef(InboxSql.u64(row, "player_id"),
                InboxSql.u32(row, "domain"), InboxSql.u64(row, "seq"), InboxSql.u32(row, "state"), InboxSql.u64(row, "request_id"),
                InboxSql.u32(row, "msg_type"), InboxSql.u32(row, "outcome_reason"), InboxSql.u32(row, "finalized_by"),
                InboxSql.u64(row, "updated_ms")), args.toArray()));
        for (FinalizedRef ref : refs) {
            InboxSql.checkRowShard(shard, ref.playerId());
        }
        return refs;
    }
}
