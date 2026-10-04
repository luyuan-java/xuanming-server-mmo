package com.game.guild.rank;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.rank.GuildRankMetrics.RankOp;
import com.game.guild.rank.GuildRankMetrics.RankOutcome;
import com.game.guild.rank.GuildRankRedis.RawPage;
import com.game.guild.rank.GuildRankRedis.RawRank;
import com.game.guild.rank.GuildRankRedis.SwapPlan;
import com.game.guild.rank.GuildRankRedis.ZoneSwap;
import com.game.guild.rules.GuildLimits;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会排行（基线 guild_repo.go:552-888 的 ZSET 部分；guild-spec §5、D16）。
 *
 * <p><b>数据</b>：权威分是 MySQL {@code guild.score}；Redis 只是读加速——全服榜 {@code xm:guild:{rank}:all}、区榜
 * {@code xm:guild:{rank}:zone:<z>}（只放 zone ≠ 0 的帮）、区索引 {@code xm:guild:{rank}:zones}（SET，代替基线 {@code SCAN guild_rank:zone:*}）。
 * 成员是帮会号的<b>无符号十进制</b>：Redis 对同分成员按成员串字典序降序排（{@code "9" > "10"}），两版同分顺序完全由这条规则决定（§5.8、N2）。
 *
 * <p><b>维护锁</b>（基线 acquireRankLock，guild_repo.go:786-816）：入榜、清榜、重建三者都在 {@code xm:guild:{rank}:lock} 下进行，读不加锁。
 * 锁让重建与增量写互斥——重建读完 MySQL 之后、换榜之前的增量 ZADD / ZREM 会被换榜覆盖（丢更新或复活已解散的帮）。与基线的差异（D16）：
 * <ul>
 *   <li>TTL 30 s（基线 5 min），重建持锁期间每 10 s 比较后续期（请求路径的入榜 / 清榜受 3.5 s 请求预算约束，不续期）：
 *       持锁实例崩溃后最多卡 30 s（修 §9.1 第 6 条）。</li>
 *   <li>请求路径（建帮入榜、解散清榜）等锁的上限是 {@code min(剩余预算 − 300 ms, 5 s)}，拿不到只记日志放弃（与基线「失败只记日志」同结局），
 *       不让它吃掉回包装配的预算。</li>
 *   <li>写脚本先比较锁令牌：锁过期被别人接管之后，迟到的写什么都不做（基线没有这道围栏）。</li>
 * </ul>
 *
 * <p><b>线程</b>：全部公开方法在调用线程上阻塞（请求路径是 guild-worker，重建是启动线程；AGENTS.md §3），续期在注入的调度器上异步进行。
 */
public final class GuildRanks {

    private static final Logger log = LoggerFactory.getLogger(GuildRanks.class);

    /** 重建持锁期间的续期间隔（D16：TTL 30 s，按 10 s 续期）。 */
    static final long RENEW_EVERY_MS = 10_000L;

    /** 启动期重建时每次 Redis 调用的等待上限（基线用 context.Background()，没有上限；这里给一个宽松的界，免得启动永远卡住）。 */
    static final long REBUILD_CALL_TIMEOUT_MS = 10_000L;

    /** 重建时每次往临时键 ZADD 的成员数（Lua unpack 的参数个数要远小于 LUAI_MAXCSTACK = 8000）。 */
    static final int REBUILD_BATCH = 256;

    /** MySQL G10 全表扫的一行：{@code SELECT guild_id, zone_id, score FROM guild}。zone_id 是 uint32（按无符号看）。 */
    public record RankRow(long guildId, int zoneId, long score) {
    }

    /**
     * 重建的数据源（基线 RebuildRanks 的 G10，guild_repo.go:628-633）：在调用线程上把全部帮会逐行交给 {@code sink}。
     * 抛出的异常原样上抛（重建失败，启动拒绝）。
     */
    @FunctionalInterface
    public interface RankSource {
        void scan(Consumer<RankRow> sink);
    }

    /**
     * 排行上的一条。
     *
     * @param rank 名次，1 起；按 uint32 语义（同基线 {@code uint32(start) + uint32(i) + 1}，回包直接 {@code setRank(int)}）
     * @param score 基线 {@code int64(z.Score)}：分数在 ZSET 里是 double，超过 2^53 丢精度（§5.8，照搬）
     */
    public record RankEntry(long guildId, long score, int rank) {
    }

    /**
     * 一页排行。
     *
     * @param total 榜长（ZCARD）；回包的 {@code total_count} 是 uint32，调用方 {@code (int) total} 即基线的 {@code uint32(total)}
     */
    public record RankPage(List<RankEntry> entries, long total) {

        public RankPage {
            entries = List.copyOf(entries);
        }
    }

    /** 重建的结果（日志与断言用）。 */
    public record RebuildResult(int guilds, int zones) {
    }

    private final GuildRankRedis redis;
    private final ScheduledExecutorService scheduler;
    private final GuildRankMetrics metrics;
    private final long lockTtlMillis;
    private final long renewEveryMillis;
    private final long maxWaitMillis;
    private final long pollMillis;
    private final long reserveMillis;
    private final long tmpTtlMillis;

    /**
     * @param scheduler 维护锁续期用的调度器（装配方持有、负责关闭；可与缓存失效的后台调度器共用）
     */
    public GuildRanks(GuildRankRedis redis, ScheduledExecutorService scheduler, GuildRankMetrics metrics) {
        this(redis, scheduler, metrics, GuildLimits.RANK_LOCK_TTL_MS, RENEW_EVERY_MS, GuildLimits.RANK_LOCK_MAX_WAIT_MS,
                GuildLimits.RANK_LOCK_POLL_MS, GuildLimits.RANK_LOCK_REQUEST_RESERVE_MS, GuildLimits.RANK_REBUILD_TMP_TTL_MS);
    }

    /** 单测用：可缩短锁 TTL / 续期 / 等锁上限。 */
    GuildRanks(GuildRankRedis redis, ScheduledExecutorService scheduler, GuildRankMetrics metrics, long lockTtlMillis,
               long renewEveryMillis, long maxWaitMillis, long pollMillis, long reserveMillis, long tmpTtlMillis) {
        this.redis = redis;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.lockTtlMillis = lockTtlMillis;
        this.renewEveryMillis = renewEveryMillis;
        this.maxWaitMillis = maxWaitMillis;
        this.pollMillis = pollMillis;
        this.reserveMillis = reserveMillis;
        this.tmpTtlMillis = tmpTtlMillis;
    }

    // ------------------------------------------------------------------ 写（请求路径：失败只记日志，永不抛）

    /**
     * 单帮入榜 / 改分（基线 UpdateGuildScore 的 ZSET 部分，guild_repo.go:608-616；建帮以 0 分入榜，guild_logic.go:283-287）：
     * 持锁一段 Lua 同时 {@code ZADD all}、{@code ZADD zone:<z>}（z ≠ 0）、{@code SADD zones <z>}。
     *
     * <p>失败（等锁超时、Redis 故障、锁中途失效）只记 ERROR 与指标——权威分在 MySQL，缺口由下次启动的重建自愈（§9.1 第 10 条）。
     *
     * @param zoneId 帮会的权威归属区（建帮事务写入的值）；0 = 只进全服榜
     * @return 是否已写入
     */
    public boolean add(long guildId, int zoneId, long score, Deadline deadline) {
        String member = Long.toUnsignedString(guildId);
        try (Lease lease = acquireForRequest(RankOp.ADD, deadline)) {
            if (lease == null) {
                log.error("[guild] 等排行维护锁超时，放弃入榜 guild={} zone={}（下次启动重建自愈）", member,
                        Integer.toUnsignedString(zoneId));
                return false;
            }
            String zoneKey = zoneId != 0 ? RedisKeys.guildRankZone(zoneId) : null;
            String zoneMember = zoneId != 0 ? Integer.toUnsignedString(zoneId) : null;
            boolean written = deadline.await(redis.add(RedisKeys.guildRankLock(), lease.token, RedisKeys.guildRankAll(),
                    RedisKeys.guildRankZones(), zoneKey, zoneMember, Long.toString(score), member), "入榜 " + member);
            if (!written) {
                metrics.rankOp(RankOp.ADD, RankOutcome.ERROR);
                log.error("[guild] 入榜时排行维护锁已失效，什么都没写 guild={}（下次启动重建自愈）", member);
                return false;
            }
            metrics.rankOp(RankOp.ADD, RankOutcome.OK);
            return true;
        } catch (RuntimeException e) {
            metrics.rankOp(RankOp.ADD, RankOutcome.ERROR);
            log.error("[guild] 入榜失败 guild={} zone={}（下次启动重建自愈）: {}", member, Integer.toUnsignedString(zoneId),
                    rootMessage(e));
            return false;
        }
    }

    /**
     * 从全服榜与<b>全部</b>区榜移除（基线 RemoveGuildFromRank，guild_repo.go:700-752）：持锁先 {@code SMEMBERS zones}，再一段 Lua
     * 对 {@code all}、每个区榜、以及 {@code hintZone} 的区榜逐个 {@code ZREM}。持锁期间不会有新区榜键出现，两步之间无竞态。
     *
     * <p>{@code hintZone} 必须是解散事务内读到的 zone（不能是缓存里可能陈旧一个 TTL 的值）。与基线的差异：基线先非锁定读一次 MySQL 的
     * 权威 zone（G8），但随后无论如何都扫遍全部区榜；Java 的区索引覆盖了一切经入榜 / 重建写过的区榜，所以权威 zone 改变不了结果，
     * 这一读省掉（也就没有「hint 与权威 zone 不一致」的那条 ERROR 日志）。
     *
     * <p>失败只记 ERROR 与指标（基线 guild_logic.go:503-508），永不抛。
     *
     * @return 是否已清
     */
    public boolean remove(long guildId, int hintZone, Deadline deadline) {
        String member = Long.toUnsignedString(guildId);
        try (Lease lease = acquireForRequest(RankOp.REMOVE, deadline)) {
            if (lease == null) {
                log.error("[guild] 等排行维护锁超时，放弃清榜 guild={} zone={}（下次启动重建自愈）", member,
                        Integer.toUnsignedString(hintZone));
                return false;
            }
            Set<String> zoneMembers = deadline.await(redis.members(RedisKeys.guildRankZones()), "读区榜索引");
            Set<String> keys = new LinkedHashSet<>();
            keys.add(RedisKeys.guildRankAll());
            for (int zone : parseZones(zoneMembers)) {
                keys.add(RedisKeys.guildRankZone(zone));
            }
            if (hintZone != 0) {
                keys.add(RedisKeys.guildRankZone(hintZone)); // 区榜可能还没进索引；ZREM 对不存在的键是空操作
            }
            boolean removed = deadline.await(redis.remove(RedisKeys.guildRankLock(), lease.token, List.copyOf(keys), member),
                    "清榜 " + member);
            if (!removed) {
                metrics.rankOp(RankOp.REMOVE, RankOutcome.ERROR);
                log.error("[guild] 清榜时排行维护锁已失效，什么都没删 guild={}（下次启动重建自愈）", member);
                return false;
            }
            metrics.rankOp(RankOp.REMOVE, RankOutcome.OK);
            return true;
        } catch (RuntimeException e) {
            metrics.rankOp(RankOp.REMOVE, RankOutcome.ERROR);
            log.error("[guild] 清榜失败 guild={} zone={}（下次启动重建自愈）: {}", member, Integer.toUnsignedString(hintZone),
                    rootMessage(e));
            return false;
        }
    }

    // ------------------------------------------------------------------ 重建（启动期：失败抛，拒启）

    /**
     * 从 MySQL 全量重建全服榜与全部区榜（基线 RebuildRanks，guild_repo.go:620-698；每次启动都跑，失败拒启）：
     * 取锁（最多等 5 s）→ G10 逐行写进带 PEXPIRE 10 min 的临时键（修 §9.1 第 7 条：进程中途死亡不泄漏）→ {@code SMEMBERS zones} 得旧区 →
     * 一段 Lua 原子换榜（比较锁令牌、核对临时键成员数、{@code DEL all}、{@code DEL} 旧区榜、{@code RENAME} 临时 → 正式并 {@code PERSIST}、
     * 重写区索引）。能修复单项缺失、旧值、区榜丢失、解散后的幽灵条目。最后无论成败都删掉临时键。
     *
     * @throws IllegalStateException 等锁超时、锁中途失效、临时键不完整
     * @throws DependencyException   Redis 故障
     * @throws RuntimeException      数据源抛出的异常（原样）
     */
    public RebuildResult rebuild(RankSource source) {
        Lease lease;
        try {
            lease = acquire(maxWaitMillis, () -> Deadline.after(REBUILD_CALL_TIMEOUT_MS), true);
        } catch (RuntimeException e) {
            metrics.rankOp(RankOp.REBUILD, RankOutcome.ERROR);
            throw e;
        }
        if (lease == null) {
            metrics.rankOp(RankOp.REBUILD, RankOutcome.LOCK_TIMEOUT);
            throw new IllegalStateException("等排行维护锁超时（" + maxWaitMillis + " ms），无法重建帮会排行");
        }
        String tmpToken = UUID.randomUUID().toString();
        TempKeys temp = new TempKeys(tmpToken);
        try (lease) {
            source.scan(temp::add);
            temp.flushAll();
            Set<String> oldMembers = await(redis.members(RedisKeys.guildRankZones()), "读区榜索引");
            List<String> oldZoneKeys = new ArrayList<>();
            for (int zone : parseZones(oldMembers)) {
                oldZoneKeys.add(RedisKeys.guildRankZone(zone));
            }
            List<ZoneSwap> zones = new ArrayList<>();
            for (Map.Entry<Integer, Long> zone : temp.zoneCounts.entrySet()) {
                int zoneId = zone.getKey();
                zones.add(new ZoneSwap(RedisKeys.guildRankTmpZone(tmpToken, zoneId), RedisKeys.guildRankZone(zoneId),
                        Integer.toUnsignedString(zoneId), zone.getValue()));
            }
            long swapped = await(redis.swap(new SwapPlan(RedisKeys.guildRankLock(), lease.token, RedisKeys.guildRankAll(),
                    RedisKeys.guildRankZones(), temp.allKey, temp.allCount, oldZoneKeys, zones)), "换榜");
            if (swapped == 0) {
                throw new IllegalStateException("换榜时排行维护锁已失效（被别的实例接管），本次重建作废");
            }
            if (swapped != 1) {
                throw new IllegalStateException("换榜前核对临时键成员数不符（临时键过期或被淘汰），本次重建作废");
            }
            metrics.rankOp(RankOp.REBUILD, RankOutcome.OK);
            log.info("[guild] 帮会排行已从 MySQL 重建：{} 个帮会、{} 个区榜", temp.rows, zones.size());
            return new RebuildResult(temp.rows, zones.size());
        } catch (RuntimeException e) {
            metrics.rankOp(RankOp.REBUILD, RankOutcome.ERROR);
            throw e;
        } finally {
            temp.deleteQuietly();
        }
    }

    /** 重建期间累积的临时键批次与计数。只在重建线程上使用。 */
    private final class TempKeys {

        final String token;
        final String allKey;
        final List<String> allBatch = new ArrayList<>();
        long allCount;
        /** zone（按无符号升序，换榜计划与日志稳定）→ 已写入的成员数。 */
        final TreeMap<Integer, Long> zoneCounts = new TreeMap<>(Integer::compareUnsigned);
        final TreeMap<Integer, List<String>> zoneBatches = new TreeMap<>(Integer::compareUnsigned);
        int rows;

        TempKeys(String token) {
            this.token = token;
            this.allKey = RedisKeys.guildRankTmpAll(token);
        }

        void add(RankRow row) {
            rows++;
            String score = Long.toString(row.score());
            String member = Long.toUnsignedString(row.guildId());
            allBatch.add(score);
            allBatch.add(member);
            if (allBatch.size() >= REBUILD_BATCH * 2) {
                allCount += flush(allKey, allBatch);
            }
            if (row.zoneId() != 0) {
                zoneCounts.putIfAbsent(row.zoneId(), 0L);
                List<String> batch = zoneBatches.computeIfAbsent(row.zoneId(), z -> new ArrayList<>());
                batch.add(score);
                batch.add(member);
                if (batch.size() >= REBUILD_BATCH * 2) {
                    zoneCounts.merge(row.zoneId(), flush(RedisKeys.guildRankTmpZone(token, row.zoneId()), batch), Long::sum);
                }
            }
        }

        void flushAll() {
            if (!allBatch.isEmpty()) {
                allCount += flush(allKey, allBatch);
            }
            for (Map.Entry<Integer, List<String>> batch : zoneBatches.entrySet()) {
                if (!batch.getValue().isEmpty()) {
                    zoneCounts.merge(batch.getKey(), flush(RedisKeys.guildRankTmpZone(token, batch.getKey()), batch.getValue()),
                            Long::sum);
                }
            }
        }

        private long flush(String key, List<String> batch) {
            long added = await(redis.addTemp(key, List.copyOf(batch), tmpTtlMillis), "写重建临时键");
            batch.clear();
            return added;
        }

        /** 删掉全部临时键（换榜成功后它们已被 RENAME 掉，DEL 是空操作；失败时清掉半成品）。失败只记日志：临时键带 TTL。 */
        void deleteQuietly() {
            List<String> keys = new ArrayList<>();
            keys.add(allKey);
            for (int zone : zoneCounts.keySet()) {
                keys.add(RedisKeys.guildRankTmpZone(token, zone));
            }
            try {
                await(redis.delete(keys), "删重建临时键");
            } catch (RuntimeException e) {
                log.warn("[guild] 删重建临时键失败（带 TTL，会自然过期）: {}", rootMessage(e));
            }
        }
    }

    // ------------------------------------------------------------------ 读（失败抛 DependencyException）

    /**
     * 一页排行（基线 GetGuildRankPage，guild_repo.go:818-858）：键 zone ≠ 0 用区榜、否则全服榜；一段只读 Lua 同时取
     * {@code ZCARD} 与 {@code ZREVRANGE WITHSCORES}（基线两条命令，结果相同且总数与本页一致）。
     *
     * <p>{@code page} / {@code pageSize} 按 uint32 看，夹取与缺省值（0 → 20 / 1、客户端夹到 50）是调用方的事（§5.6）；这里照基线：
     * total、page、size 任一为 0 → 空页；{@code start = (page−1)×size} 按 <b>uint64</b> 算（不会环绕回榜首，rank_page_test.go），
     * {@code start ≥ total} → 空页；名次 = start + i + 1。成员解析失败得 0（基线忽略 ParseUint 的错误）。
     *
     * @throws DependencyException Redis 故障或预算用完
     */
    public RankPage page(int zoneId, int page, int pageSize, Deadline deadline) {
        String key = zoneId != 0 ? RedisKeys.guildRankZone(zoneId) : RedisKeys.guildRankAll();
        long p = Integer.toUnsignedLong(page);
        long size = Integer.toUnsignedLong(pageSize);
        long start = p == 0 ? 0 : (p - 1) * size; // ≤ (2^32−2)(2^32−1) < 2^64：按无符号看不溢出
        long effectiveSize = p == 0 ? 0 : size;   // 页码为 0 只要总数
        RawPage raw = deadline.await(redis.page(key, Long.toUnsignedString(start), effectiveSize), "读排行 " + key);
        List<RankEntry> entries = new ArrayList<>(raw.members().size());
        for (int i = 0; i < raw.members().size(); i++) {
            entries.add(new RankEntry(parseMember(raw.members().get(i)), toScore(raw.scores().get(i), key),
                    (int) (start + i + 1)));
        }
        return new RankPage(entries, raw.total());
    }

    /**
     * 单帮名次（基线 GetGuildRank，guild_repo.go:860-888）：一段只读 Lua 同时取 {@code ZREVRANK} 与 {@code ZSCORE}（D16：基线两步之间条目被删
     * 会让 ZSCORE 回 nil 被当成故障，§9.1 第 8 条；合并后该竞态答「未上榜」）。不在榜上为 empty（调用方回 14007）。
     *
     * @throws DependencyException Redis 故障或预算用完
     */
    public Optional<RankEntry> rankOf(long guildId, int zoneId, Deadline deadline) {
        String key = zoneId != 0 ? RedisKeys.guildRankZone(zoneId) : RedisKeys.guildRankAll();
        RawRank raw = deadline.await(redis.rank(key, Long.toUnsignedString(guildId)), "读名次 " + key);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(new RankEntry(guildId, toScore(raw.score(), key), (int) (raw.index() + 1)));
    }

    // ------------------------------------------------------------------ 维护锁

    /** 持有中的维护锁：关闭时停续期、按令牌异步释放（失败只记日志，同基线 guild_repo.go:800-806）。 */
    private final class Lease implements AutoCloseable {

        final String token;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> renewal;

        Lease(String token) {
            this.token = token;
        }

        void startRenewal() {
            try {
                renewal = scheduler.scheduleAtFixedRate(this::renew, renewEveryMillis, renewEveryMillis,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.warn("[guild] 排行维护锁续期调度器已关闭，本次持锁不续期（TTL {} ms）", lockTtlMillis);
            }
        }

        private void renew() {
            if (closed.get()) {
                return;
            }
            call(() -> redis.renewLock(RedisKeys.guildRankLock(), token, lockTtlMillis)).whenComplete((held, error) -> {
                if (error != null) {
                    log.warn("[guild] 排行维护锁续期失败（下个周期再试）: {}", rootMessage(error));
                } else if (!Boolean.TRUE.equals(held) && !closed.get()) {
                    log.error("[guild] 排行维护锁已失效（过期后被接管），持有者的后续写会被令牌围栏拒绝");
                    cancelRenewal();
                }
            });
        }

        private void cancelRenewal() {
            ScheduledFuture<?> task = renewal;
            if (task != null) {
                task.cancel(false);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelRenewal();
            releaseQuietly(token);
        }
    }

    /**
     * 请求路径取锁：等锁上限 {@code min(剩余预算 − 300 ms, 5 s)}（下限 0：只试一次）；超时为 null 并计 lock_timeout。
     * 不续期：持锁期间的每次 Redis 调用都受请求预算（3.5 s）约束，远短于锁 TTL。
     */
    private Lease acquireForRequest(RankOp op, Deadline deadline) {
        long wait = Math.max(0, Math.min(deadline.remainingMillis() - reserveMillis, maxWaitMillis));
        Lease lease = acquire(wait, () -> deadline, false);
        if (lease == null) {
            metrics.rankOp(op, RankOutcome.LOCK_TIMEOUT);
        }
        return lease;
    }

    /** 每次 Redis 调用的等待上界。 */
    @FunctionalInterface
    private interface CallBound {
        Deadline next();
    }

    /**
     * 取维护锁：每 {@code pollMillis} 试一次，最多等 {@code waitMillis}（至少试一次）。拿到 → （{@code renew} 时启动续期）返回；超时 → null；
     * Redis 报错 → {@link DependencyException}（不再重试，同基线）。抢锁的命令等超时或失败时它可能其实已经成功，所以先按令牌异步释放一次，
     * 免得白占 30 s。
     */
    private Lease acquire(long waitMillis, CallBound bound, boolean renew) {
        String token = UUID.randomUUID().toString();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (true) {
            boolean acquired;
            try {
                acquired = Boolean.TRUE.equals(bound.next().await(
                        call(() -> redis.tryLock(RedisKeys.guildRankLock(), token, lockTtlMillis)), "抢排行维护锁"));
            } catch (DependencyException e) {
                releaseQuietly(token);
                throw e;
            }
            if (acquired) {
                Lease lease = new Lease(token);
                if (renew) {
                    lease.startRenewal();
                }
                return lease;
            }
            long left = until - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(pollMillis, TimeUnit.NANOSECONDS.toMillis(left))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DependencyException("等排行维护锁被中断", e);
            }
        }
    }

    private void releaseQuietly(String token) {
        call(() -> redis.unlock(RedisKeys.guildRankLock(), token)).whenComplete((released, error) -> {
            if (error != null) {
                log.error("[guild] 释放排行维护锁失败（最多占用 {} ms 后自动过期）: {}", lockTtlMillis, rootMessage(error));
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    /** 重建路径的等待（每次调用一个独立的 10 s 上界）。 */
    private static <T> T await(CompletionStage<T> stage, String what) {
        return Deadline.after(REBUILD_CALL_TIMEOUT_MS).await(stage, what);
    }

    /** 把同步抛出的异常折成失败的 future（Redis 实现 / 假实现都可能同步抛）。 */
    private static <T> CompletableFuture<T> call(Supplier<CompletionStage<T>> op) {
        try {
            return op.get().toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 区索引成员 → zone（无符号十进制；解不开的记 WARN 跳过——索引只由本类写，出现即说明被外部改过）。 */
    private static List<Integer> parseZones(Set<String> members) {
        List<Integer> zones = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                int zone = Integer.parseUnsignedInt(member);
                if (zone != 0) {
                    zones.add(zone);
                }
            } catch (NumberFormatException e) {
                log.warn("[guild] 区榜索引里有解不开的成员，跳过: {}", member);
            }
        }
        zones.sort(Integer::compareUnsigned);
        return zones;
    }

    /** 成员 → 帮会号（基线 {@code strconv.ParseUint} 的错误被忽略，得 0；§9.1 第 9 条照搬）。 */
    static long parseMember(String member) {
        try {
            return Long.parseUnsignedLong(member);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * ZSET 分数原串 → int64（基线 {@code int64(z.Score)}）。Redis 回的是 double 的十进制（可能带指数）；{@code inf} / {@code -inf}
     * 照 Go 的 ParseFloat 接受。Java 的 {@code (long) double} 对超出 int64 的值饱和，Go 在 amd64 上得 MinInt64——分数来源只有 int64，
     * 不会出现。解不开 → {@link DependencyException}（Redis 回了不是数字的东西）。
     */
    static long toScore(String raw, String key) {
        String text = raw.trim();
        double value;
        switch (text.toLowerCase(Locale.ROOT)) {
            case "inf", "+inf" -> value = Double.POSITIVE_INFINITY;
            case "-inf" -> value = Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    value = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new DependencyException("排行分数无法解析 " + key + ": " + raw, e);
                }
            }
        }
        return (long) value;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return e.getMessage() + (root == e ? "" : ": " + root);
    }
}
