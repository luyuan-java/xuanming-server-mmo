package com.game.inbox.maintenance;

import com.game.common.deadline.Deadline;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.rules.InboxShards;
import com.game.inbox.rules.LedgerProofs;
import com.game.inbox.rules.LedgerProofs.FinalizedRef;
import com.game.inbox.rules.LedgerProofs.LedgerGap;
import com.game.inbox.store.LedgerReader;
import com.game.player.store.inbox.InboxLedgerRules;
import com.game.player.store.state.InboxDomainCursorState;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定时对账的一轮（mmorpg {@code reconcile.go} 的 {@code ReconcileRecent}；设计 §9.3，PARITY 收件箱行第 ⑥ 条）。只读，不改任何状态：
 * <ol>
 *   <li>从起始分片环形扫描 64 片，逐片按键集翻页读窗口内由消费方终结的行（最新的先读）；单片至多 {@link Limits#perShard} 行，读满后再探一行、
 *       确有剩余才算这一片截断，然后继续下一片；新出现的玩家数到 {@link Limits#maxPlayers} 时整轮停在当前分片。触到任一上限计
 *       {@code reconcile.truncated} 并打 ERROR；</li>
 *   <li>按玩家以有界并发读已落盘账本（每次带 {@link #LEDGER_READ_TIMEOUT}）；</li>
 *   <li>逐个玩家按 {@link LedgerProofs#finalizedGap}（与 ReconcilePlayer 同一个谓词）判定，每处不一致写一行带 player_id 的 ERROR
 *       （player_id 只进日志）；读不到存档计 absent（结论未知，不告警），读失败计 error，账本不合法计 invalid（结论未知，不判「无差异」）。</li>
 * </ol>
 * <b>起始分片步长轮转</b>：墙钟落在第 k = now / interval 个间隔，起点 = k × 4 % 64（{@link #startShard}），不依赖内存状态。步长 4 = 每轮玩家上限
 * 2000 ÷ 单片行上限 500：一片至多收 500 名新玩家，所以起点之后的前 4 片每轮必定读完；相邻轮次首尾相接，连续 16 轮必然把 64 片各读完一遍。
 */
final class ReconcileRound {

    private static final Logger log = LoggerFactory.getLogger(ReconcileRound.class);

    /** 对账读一次已落盘账本的上限。 */
    static final Duration LEDGER_READ_TIMEOUT = Duration.ofSeconds(2);

    /** 一轮的工作量上限；生产取 {@link #DEFAULT}，单测注入小值。 */
    record Limits(int pageSize, int perShard, int maxPlayers, int readConcurrency) {
        static final Limits DEFAULT = new Limits(100, 500, 2000, 8);
    }

    /** 相邻两轮的起始分片相差几片：每轮玩家上限 ÷ 单片行上限（= 4）。 */
    static final int START_STRIDE = Limits.DEFAULT.maxPlayers() / Limits.DEFAULT.perShard();
    /** 截断持续时，每片至多隔这么多轮被读完一次（⌈64 / 4⌉ = 16）。 */
    static final int FULL_CYCLE_ROUNDS = (InboxShards.SHARD_COUNT + START_STRIDE - 1) / START_STRIDE;

    /** 读一个分片里排在 before 之后（更旧）的一页消费方终结行；before 为 null 从最新的一行读起。 */
    @FunctionalInterface
    interface PageReader {
        List<FinalizedRef> read(int shard, FinalizedRef before, int limit) throws SQLException;
    }

    /**
     * 一轮在一个分片上的读数。complete = 这一片窗口内的行本轮全部核对过（没被单片上限截掉、不是玩家数触顶时停下的那一片、每名玩家都有确定的判定）；
     * 为 false 时 inconsistent 只是下界。
     */
    record ShardTally(int shard, int inconsistent, boolean complete) {
    }

    /** 一轮的结果。 */
    record Result(int inconsistent, int playersPlanned, int playersChecked, boolean truncated, List<ShardTally> shards) {
    }

    private ReconcileRound() {
    }

    /** 一轮的起始分片：k = now / interval，起点 = k × 4 % 64（先取模再乘，不溢出）。 */
    static int startShard(long nowMs, long intervalMs) {
        if (intervalMs <= 0) {
            return 0;
        }
        long k = Long.remainderUnsigned(Long.divideUnsigned(nowMs, intervalMs), InboxShards.SHARD_COUNT);
        return (int) (k * START_STRIDE % InboxShards.SHARD_COUNT);
    }

    /**
     * 跑一轮。{@code stop} 为真（易主 / 关停 / 本轮预算用完）时不再发起新的读，返回已判定部分；调用方据此决定记 timeout 还是不计。
     *
     * @throws SQLException 收集阶段读失败（本轮没有分片读数，gauge 保持原值）
     */
    static Result run(int startShard, Limits limits, PageReader pages, LedgerReader ledgers, ExecutorService readers, BooleanSupplier stop,
                      InboxMetrics metrics) throws SQLException {
        Collected scan = collect(Math.floorMod(startShard, InboxShards.SHARD_COUNT), limits, pages, stop);
        boolean truncated = !scan.capped.isEmpty() || scan.playerCapHit;
        if (truncated) {
            metrics.reconcileTruncated();
            log.error("[PlayerInbox] 对账轮次被截断（start_shard={}，扫了 {}/64 片）：{}；起始分片每个间隔前移 {} 片，截断持续时每片至多隔 {} 轮读完一次"
                            + "（没读到的分片沿用上一次读数），但被单片上限截掉的最老的行在这一片持续有新行时一直核对不到；缩短 game.inbox.reconcile-window 或调大上限",
                    startShard, scan.scanned, scan.summary(limits), START_STRIDE, FULL_CYCLE_ROUNDS);
        }
        List<ShardTally> tallies = new ArrayList<>(scan.scanned);
        Map<Integer, Integer> tallyIndex = new HashMap<>();
        for (int i = 0; i < scan.scanned; i++) {
            int shard = (scan.start + i) % InboxShards.SHARD_COUNT;
            boolean stopped = scan.playerCapHit && shard == scan.stoppedShard;
            tallyIndex.put(shard, tallies.size());
            tallies.add(new ShardTally(shard, 0, !scan.capped.contains(shard) && !stopped));
        }

        List<Long> players = new ArrayList<>(scan.byPlayer.keySet());
        List<Future<LedgerReader.Read>> reads = new ArrayList<>(players.size());
        for (long playerId : players) {
            if (stop.getAsBoolean()) {
                reads.add(null);
                continue;
            }
            reads.add(readers.submit(() -> ledgers.read(Deadline.after(LEDGER_READ_TIMEOUT.toMillis()), playerId)));
        }

        int inconsistent = 0;
        int checked = 0;
        for (int i = 0; i < players.size(); i++) {
            long playerId = players.get(i);
            int index = tallyIndex.get(scan.shardOf.get(playerId));
            ShardTally tally = tallies.get(index);
            Future<LedgerReader.Read> future = reads.get(i);
            LedgerReader.Read read = null;
            Throwable failure = null;
            if (future != null) {
                try {
                    read = future.get(LEDGER_READ_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = e;
                } catch (Exception e) {
                    failure = e.getCause() != null ? e.getCause() : e;
                }
            }
            if (stop.getAsBoolean() && read == null) {
                // 轮次已结束（超时 / 易主 / 关停）：没读或读到一半被打断的不算读失败，留给之后的轮次
                tallies.set(index, new ShardTally(tally.shard(), tally.inconsistent(), false));
                if (future != null) {
                    future.cancel(true);
                }
                continue;
            }
            checked++;
            if (read == null) {
                tallies.set(index, new ShardTally(tally.shard(), tally.inconsistent(), false));
                metrics.reconcileLedgerRead("error");
                log.error("[PlayerInbox] 对账读不到已落盘账本 player={}: {}", Long.toUnsignedString(playerId), String.valueOf(failure));
                continue;
            }
            if (!read.found()) {
                metrics.reconcileLedgerRead("absent");
                continue;
            }
            String invalid = InboxLedgerRules.validate(read.ledger(), true);
            if (invalid != null) {
                metrics.reconcileLedgerRead("invalid");
                log.error("[PlayerInbox] 对账跳过 player={}：已落盘的收件箱账本结构不合法（{}）；结论未知，不是「无差异」", Long.toUnsignedString(playerId),
                        invalid);
                continue;
            }
            metrics.reconcileLedgerRead("found");
            int found = 0;
            for (FinalizedRef ref : scan.byPlayer.get(playerId)) {
                InboxDomainCursorState cursor = InboxLedgerRules.findCursor(read.ledger(), ref.domain());
                LedgerGap gap = LedgerProofs.finalizedGap(ref, cursor);
                if (gap == LedgerGap.NONE) {
                    continue;
                }
                found++;
                log.error("[PlayerInbox] 对账不一致 player={} domain={} seq={} state={} request_id={} gap={} ledger_decided_through={} ring_len={}："
                                + "收件箱已终结这一条，已落盘账本里却没有它的结局（回档或存档丢数据）；可能需要人工重下",
                        Long.toUnsignedString(playerId), ref.domain(), Long.toUnsignedString(ref.seq()), ref.state(),
                        Long.toUnsignedString(ref.requestId()), gap.label(), cursor == null ? 0 : Long.toUnsignedString(cursor.getDecidedThroughSeq()),
                        cursor == null ? 0 : cursor.getRecentCount());
            }
            inconsistent += found;
            ShardTally current = tallies.get(index);
            tallies.set(index, new ShardTally(current.shard(), current.inconsistent() + found, current.complete()));
        }
        return new Result(inconsistent, players.size(), checked, truncated, List.copyOf(tallies));
    }

    /** 一轮收集到的待核对行与分片覆盖情况。 */
    private static final class Collected {
        /** 玩家首次出现的顺序（LinkedHashMap）：读账本、判定与日志都按它走，结果确定。 */
        final Map<Long, List<FinalizedRef>> byPlayer = new LinkedHashMap<>();
        /** 每名玩家的行是从哪一片读出来的。 */
        final Map<Long, Integer> shardOf = new HashMap<>();
        final List<Integer> capped = new ArrayList<>();
        final int start;
        int scanned;
        boolean playerCapHit;
        int stoppedShard;

        Collected(int start) {
            this.start = start;
        }

        String summary(Limits limits) {
            List<String> parts = new ArrayList<>();
            if (!capped.isEmpty()) {
                parts.add(capped.size() + " 片 " + capped + " 读满单片上限 " + limits.perShard() + " 行（最新的已核对、最老的跳过）");
            }
            if (playerCapHit) {
                parts.add("玩家数到上限 " + limits.maxPlayers() + "，停在 shard=" + stoppedShard + "，还有 " + (InboxShards.SHARD_COUNT - scanned)
                        + " 片没读到");
            }
            return String.join("；", parts);
        }
    }

    private static Collected collect(int startShard, Limits limits, PageReader pages, BooleanSupplier stop) throws SQLException {
        Collected scan = new Collected(startShard);
        for (int i = 0; i < InboxShards.SHARD_COUNT; i++) {
            if (stop.getAsBoolean()) {
                throw new SQLException("对账收集被打断（易主 / 关停 / 预算用完）");
            }
            int shard = (startShard + i) % InboxShards.SHARD_COUNT;
            scan.scanned++;
            if (collectShard(scan, shard, limits, pages)) {
                scan.playerCapHit = true;
                scan.stoppedShard = shard;
                break;
            }
        }
        return scan;
    }

    /** 读一片；返回 true = 玩家数到上限，整轮该停了。 */
    private static boolean collectShard(Collected scan, int shard, Limits limits, PageReader pages) throws SQLException {
        FinalizedRef before = null;
        int taken = 0;
        while (true) {
            int limit = Math.min(limits.pageSize(), limits.perShard() - taken);
            if (limit <= 0) {
                if (!pages.read(shard, before, 1).isEmpty()) {
                    scan.capped.add(shard);
                }
                return false;
            }
            List<FinalizedRef> page = pages.read(shard, before, limit);
            for (FinalizedRef ref : page) {
                List<FinalizedRef> rows = scan.byPlayer.get(ref.playerId());
                if (rows == null) {
                    if (scan.byPlayer.size() >= limits.maxPlayers()) {
                        return true;
                    }
                    rows = new ArrayList<>();
                    scan.byPlayer.put(ref.playerId(), rows);
                    scan.shardOf.put(ref.playerId(), shard);
                }
                rows.add(ref);
                taken++;
            }
            if (page.size() < limit) {
                return false;
            }
            before = page.get(page.size() - 1);
        }
    }

    /**
     * 一个任期内各分片最近一次对账读数（评审 R1），{@code reconcile.inconsistent.rows} 取它们的和：本轮读完的分片用本轮读数覆盖（处置之后能降下来）；
     * 只核对了一部分的分片取 max(旧, 本轮)（发现的不一致是确凿的下界）；本轮没读到的分片保留旧读数。只由对账那一个线程读写。
     */
    static final class Tally {
        private final int[] byShard = new int[InboxShards.SHARD_COUNT];

        int apply(List<ShardTally> shards) {
            for (ShardTally s : shards) {
                int shard = Math.floorMod(s.shard(), InboxShards.SHARD_COUNT);
                byShard[shard] = s.complete() ? s.inconsistent() : Math.max(byShard[shard], s.inconsistent());
            }
            int total = 0;
            for (int n : byShard) {
                total += n;
            }
            return total;
        }
    }
}
