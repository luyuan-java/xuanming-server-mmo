package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.placement.PlacementRecords;
import com.game.match.proto.BattlePlacement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RBatch;
import org.redisson.api.RScript;
import org.redisson.api.RScriptAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SpectateStore} 的生产实现（spectate-spec §4.2、§4.3）：每个方法一段 Lua（{@link SpectateScripts}），全部键经 {@link RedisKeys} 生成、
 * 同在 {@code {match}} 一个槽——观战标记 {@code xm:{match}:watching:<pid>}、可观战索引 {@code xm:{match}:watchable}，以及 6.4 的票据与落点记录
 * （票据只看在不在；落点只读，外加 S_W_EVICT 的两种有条件删除）。
 *
 * <ul>
 *   <li><b>阻塞但不占锁</b>：用 Redisson 的异步 API 发出，再在调用线程上等到截止（{@link Deadline#await}）——不持 {@code synchronized}，
 *       可以在 163 / gather 的虚拟线程上调。</li>
 *   <li><b>失败一律 {@link Deadline.DependencyException}</b>：Redis 报错、等过了截止、回复形状不对。截止在发出之前就已经用完时<b>不发</b>
 *       （什么都没读、什么都没写）；等超时的那一种，命令还在路上、可能随后执行——对可变方法这就是「结局不明」。
 *       其余可变脚本靠「按值 / 按条件 / 比 attempt」挡得住迟到的副本，只有 {@link #acquire} 挡不住（见该方法）：它在放弃等待之后，
 *       等在途的命令有了结局再按值释放一次。
 *       损坏的落点记录不是失败，是 {@link Record.Corrupt}（163 回 16004、列表跳过，都不剔除）。</li>
 *   <li><b>全部脚本以 {@link RScript.Mode#READ_WRITE} 执行</b>（读主库，理由见 {@link SpectateScripts}）；只有清扫器采样用的 {@code ZCARD}
 *       是一条普通的读命令（主从部署下读到从库的旧值无妨：它只喂一个 gauge）。</li>
 *   <li><b>编解码</b>：{@link ByteArrayCodec}，键是 ASCII 文本，参数与回复里的串都是字节。落点的 {@code pb} 是 protobuf 字节，原样交给
 *       {@link PlacementRecords}（落点怎么算损坏只有那一处定义）。<b>索引成员按字节一一对应成字符</b>（ISO-8859-1）：正常成员是十进制数字，
 *       不受影响；任何脏成员（含不是合法 UTF-8 的字节串）读出来再原样交回 {@link Eviction.Invalid} 都摘得掉，不会因为转码失真而永远留在索引里。
 *       <b>标记值按 UTF-8</b>：正常值是 ASCII；人工写进去的文本脏值原样读出、按原串删得掉。唯一删不掉的是「不是合法 UTF-8 的字节串」这种脏标记
 *       （解码失真，按值比不上），它只能等 360 s 的 TTL——xm-match 自己从不写这样的值。</li>
 *   <li>两个 {@code *Async} 方法发出即返回、永不抛：{@link #releaseAsync} 是一条 {@code EVAL}；{@link #evictAsync} 用一个管道（{@code RBatch}）
 *       一次发出整批，占一条连接、一次往返，批里各条互不影响（管道不是事务，某一条报错不影响其余）。失败只记日志。</li>
 * </ul>
 * 入参校验（抢标记的值非空、删标记的值非 null、{@code r ∈ [0, 1)}、{@code limit ≥ 1}）在发出之前做，违反是调用方的 bug
 * （{@link IllegalArgumentException}）；玩家号、战斗号不校验（0 号只是读写一把没人用的键）。
 * 线程安全，无本地状态；构造时不碰 Redis。本类不计指标（见 {@link SpectateStore} 的类注释）。
 */
public final class RedissonSpectateStore implements SpectateStore {

    private static final Logger log = LoggerFactory.getLogger(RedissonSpectateStore.class);

    /** 标记的 TTL（毫秒）：{@value MatchBudgets#WATCHING_TTL_SECONDS} s，与落点记录同寿。 */
    static final long MARK_TTL_MS = TimeUnit.SECONDS.toMillis(MatchBudgets.WATCHING_TTL_SECONDS);

    private final RedissonClient redis;
    private final String watchableKey;

    public RedissonSpectateStore(RedissonClient redis) {
        this(redis, RedisKeys.matchWatchable());
    }

    /**
     * @param watchableKey 可观战索引的键。生产恒为 {@link RedisKeys#matchWatchable()}；真 Redis 的集成测试给一把自己的键
     *                     （以生产键为前缀，所以仍在 {@code {match}} 槽）——索引是全局的一把键，共用一台 Redis 的别的测试 / 进程里的清扫器会动它
     */
    RedissonSpectateStore(RedissonClient redis, String watchableKey) {
        this.redis = Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(watchableKey, "watchableKey");
        if (!watchableKey.startsWith(RedisKeys.matchWatchable())) {
            throw new IllegalArgumentException("可观战索引的键必须以 " + RedisKeys.matchWatchable() + " 开头（同一个 hash tag）: " + watchableKey);
        }
        this.watchableKey = watchableKey;
    }

    // ================================================================ 观战标记

    @Override
    public Entry entry(long playerId, Deadline d) {
        String what = "读观战入口状态";
        List<Object> reply = evalList(what, SpectateScripts.ENTRY, List.of(RedisKeys.matchTicket(playerId), RedisKeys.matchWatching(playerId)), d);
        requireSize(reply, 3, what);
        boolean hasTicket = flagAt(reply, 0, what);
        boolean hasMark = flagAt(reply, 1, what);
        byte[] mark = bytesAt(reply, 2, what);
        return new Entry(hasTicket, hasMark ? Optional.of(markText(mark)) : Optional.empty());
    }

    @Override
    public Acquire acquire(long playerId, String markValue, Deadline d) {
        requireNonEmptyMark(markValue);
        List<Object> keys = List.of(RedisKeys.matchTicket(playerId), RedisKeys.matchWatching(playerId));
        byte[][] args = {markBytes(markValue), ascii(Long.toString(MARK_TTL_MS))};
        // 九段可变脚本里只有这一段挡不住「迟到的重发」（标记一旦被调用方回滚，就没有东西可比）：等到截止而命令还在路上时，
        // 调用方当场发的那次释放可能先于 Redisson 重发的那一遍到达 Redis——等在途的命令有了结局，再按值释放一次
        long code = await("抢观战标记", d, () -> redis.getScript(ByteArrayCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, SpectateScripts.ACQUIRE,
                RScript.ReturnType.INTEGER, keys, (Object[]) args), () -> releaseAsync(playerId, markValue));
        if (code == SpectateScripts.ACQUIRE_OK) {
            return Acquire.OK;
        }
        if (code == SpectateScripts.ACQUIRE_QUEUED) {
            return Acquire.QUEUED;
        }
        if (code == SpectateScripts.ACQUIRE_BUSY) {
            return Acquire.BUSY;
        }
        throw new Deadline.DependencyException("抢观战标记的回复不认识: " + code);
    }

    @Override
    public boolean release(long playerId, String markValue, Deadline d) {
        requireMark(markValue);
        return evalInteger("删观战标记", SpectateScripts.RELEASE, List.of(RedisKeys.matchWatching(playerId)), d, markBytes(markValue)) == 1;
    }

    @Override
    public void releaseAsync(long playerId, String markValue) {
        requireMark(markValue);
        try {
            redis.getScript(ByteArrayCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, SpectateScripts.RELEASE, RScript.ReturnType.INTEGER,
                    List.of(RedisKeys.matchWatching(playerId)), (Object) markBytes(markValue)).whenComplete((deleted, error) -> {
                        if (error != null) {
                            log.warn("异步删观战标记失败（标记随 {} s 的 TTL 自清） player={}: {}", MatchBudgets.WATCHING_TTL_SECONDS,
                                    Long.toUnsignedString(playerId), String.valueOf(cause(error)));
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("异步删观战标记发不出去（标记随 {} s 的 TTL 自清） player={}: {}", MatchBudgets.WATCHING_TTL_SECONDS, Long.toUnsignedString(playerId),
                    String.valueOf(e));
        }
    }

    @Override
    public Map<Long, String> marksOf(List<Long> playerIds, Deadline d) {
        List<Long> ids = distinct(playerIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String what = "批量读观战标记";
        List<Object> keys = new ArrayList<>(ids.size());
        for (long playerId : ids) {
            keys.add(RedisKeys.matchWatching(playerId));
        }
        List<Object> reply = evalList(what, SpectateScripts.MARKS, keys, d);
        if (reply.size() % 2 != 0) {
            throw new Deadline.DependencyException(what + " 的回复不成对: size=" + reply.size());
        }
        Map<Long, String> out = new LinkedHashMap<>();
        for (int i = 0; i < reply.size(); i += 2) {
            long index = integerAt(reply, i, what);
            if (index < 1 || index > ids.size()) {
                throw new Deadline.DependencyException(what + " 的回复越界: 下标 " + index + "，共 " + ids.size() + " 把键");
            }
            out.put(ids.get((int) index - 1), markText(bytesAt(reply, i + 1, what)));
        }
        return out;
    }

    // ================================================================ 落点记录 + 索引的原子读

    @Override
    public Snapshot read(long battleId, Deadline d) {
        String what = "读战斗的观战快照";
        List<Object> reply = evalList(what, SpectateScripts.READ, List.of(watchableKey, RedisKeys.matchBattlePlacement(battleId)), d,
                memberBytes(SpectateRules.member(battleId)));
        requireSize(reply, 5, what);
        long now = integerAt(reply, 0, what);
        boolean published = flagAt(reply, 1, what);
        return new Snapshot(published, recordAt(battleId, reply, 2, what), now);
    }

    @Override
    public Pick pickRandom(double r, Deadline d) {
        if (!(r >= 0.0 && r < 1.0)) {
            throw new IllegalArgumentException("r 必须在 [0, 1) 内: " + r);
        }
        String what = "随机选一场可观战的战斗";
        List<Object> reply = evalList(what, SpectateScripts.PICK, List.of(watchableKey), d, ascii(Double.toString(r)),
                ascii(Long.toString(MatchBudgets.SPECTATE_STALE_MS)));
        long now = integerAt(reply, 0, what);
        if (reply.size() == 1) {
            return new Pick.None(now);
        }
        requireSize(reply, 3, what);
        return new Pick.Member(memberText(bytesAt(reply, 1, what)), scoreAt(reply, 2, what), now);
    }

    // ================================================================ 剔除与公开

    @Override
    public boolean evict(Eviction e, Deadline d) {
        EvictCall call = evictCall(e);
        return evalInteger("剔除可观战索引成员", SpectateScripts.EVICT, call.keys(), d, call.args()) == 1;
    }

    @Override
    public void evictAsync(List<Eviction> batch) {
        List<Eviction> evictions = List.copyOf(batch);
        if (evictions.isEmpty()) {
            return;
        }
        try {
            RBatch pipeline = redis.createBatch();
            RScriptAsync script = pipeline.getScript(ByteArrayCodec.INSTANCE);
            for (Eviction eviction : evictions) {
                EvictCall call = evictCall(eviction);
                script.evalAsync(RScript.Mode.READ_WRITE, SpectateScripts.EVICT, RScript.ReturnType.INTEGER, call.keys(), (Object[]) call.args());
            }
            pipeline.executeAsync().whenComplete((result, error) -> {
                if (error != null) {
                    // 管道不是事务：报出来的是其中一条的错（或整批没送达），其余各条该生效的照常生效
                    log.warn("异步剔除可观战索引成员有失败（这一批 {} 条里至少一条；残留的由下一次列表 / 选场 / 清扫再剔）: {}", evictions.size(),
                            String.valueOf(cause(error)));
                }
            });
        } catch (RuntimeException e) {
            log.warn("异步剔除可观战索引成员发不出去（一批 {} 条；残留的由下一次列表 / 选场 / 清扫再剔）: {}", evictions.size(), String.valueOf(e));
        }
    }

    @Override
    public boolean publish(BattlePlacement placement, Deadline d) {
        Objects.requireNonNull(placement, "placement");
        long battleId = placement.getBattleId();
        return evalInteger("登记可观战索引", SpectateScripts.PUBLISH, List.of(watchableKey, RedisKeys.matchBattlePlacement(battleId)), d,
                ascii(PlacementRecords.attemptField(placement.getAttempt())), memberBytes(SpectateRules.member(battleId)),
                ascii(Long.toString(placement.getCreatedAtMs()))) == 1;
    }

    // ================================================================ 列表、清扫

    @Override
    public Listed list(int limit, Deadline d) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须 ≥ 1: " + limit);
        }
        String what = "读可观战索引";
        List<Object> reply = evalList(what, SpectateScripts.LIST, List.of(watchableKey), d, ascii(Integer.toString(limit)));
        long now = integerAt(reply, 0, what);
        if (reply.size() % 2 != 1) {
            throw new Deadline.DependencyException(what + " 的回复不成对: size=" + reply.size());
        }
        List<Scored> members = new ArrayList<>((reply.size() - 1) / 2);
        for (int i = 1; i < reply.size(); i += 2) {
            members.add(new Scored(memberText(bytesAt(reply, i, what)), scoreAt(reply, i + 1, what)));
        }
        return new Listed(members, now);
    }

    @Override
    public Map<Long, Record> readPlacements(List<Long> battleIds, Deadline d) {
        List<Long> ids = distinct(battleIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String what = "批量读落点记录";
        List<Object> keys = new ArrayList<>(ids.size());
        for (long battleId : ids) {
            keys.add(RedisKeys.matchBattlePlacement(battleId));
        }
        List<Object> reply = evalList(what, SpectateScripts.RECORDS, keys, d);
        requireSize(reply, 3 * ids.size(), what);
        Map<Long, Record> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            out.put(ids.get(i), recordAt(ids.get(i), reply, 3 * i, what));
        }
        return out;
    }

    @Override
    public long sweep(Deadline d) {
        long removed = evalInteger("清扫可观战索引", SpectateScripts.SWEEP, List.of(watchableKey), d, ascii(Long.toString(MatchBudgets.SPECTATE_STALE_MS)));
        if (removed < 0) {
            throw new Deadline.DependencyException("清扫可观战索引的回复为负: " + removed);
        }
        return removed;
    }

    @Override
    public long watchableCount(Deadline d) {
        Integer size = await("读可观战索引的大小", d, () -> redis.getScoredSortedSet(watchableKey, ByteArrayCodec.INSTANCE).sizeAsync());
        return size;
    }

    // ================================================================ 内部：剔除的 KEYS / ARGV

    /** 一次 S_W_EVICT 的入参。 */
    private record EvictCall(List<Object> keys, byte[][] args) {
    }

    private EvictCall evictCall(Eviction e) {
        Objects.requireNonNull(e, "eviction");
        byte[] member = memberBytes(e.member());
        return switch (e) {
            case Eviction.Invalid invalid -> new EvictCall(List.of(watchableKey), new byte[][] {ascii(SpectateScripts.MODE_INVALID), member});
            case Eviction.Missing missing -> new EvictCall(List.of(watchableKey, RedisKeys.matchBattlePlacement(missing.battleId())),
                    new byte[][] {ascii(SpectateScripts.MODE_MISSING), member});
            case Eviction.Dead dead -> new EvictCall(List.of(watchableKey, RedisKeys.matchBattlePlacement(dead.battleId())),
                    new byte[][] {ascii(SpectateScripts.MODE_DEAD), member, ascii(PlacementRecords.attemptField(dead.attempt()))});
            case Eviction.Stale stale -> new EvictCall(List.of(watchableKey, RedisKeys.matchBattlePlacement(stale.battleId())),
                    new byte[][] {ascii(SpectateScripts.MODE_STALE), member, ascii(Long.toString(stale.cutoffMs()))});
        };
    }

    // ================================================================ 内部：发出与等待

    private long evalInteger(String what, String lua, List<Object> keys, Deadline d, byte[]... args) {
        Long reply = await(what, d, () -> redis.getScript(ByteArrayCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, lua,
                RScript.ReturnType.INTEGER, keys, (Object[]) args));
        return reply;
    }

    private List<Object> evalList(String what, String lua, List<Object> keys, Deadline d, byte[]... args) {
        return await(what, d, () -> redis.getScript(ByteArrayCodec.INSTANCE).<List<Object>>evalAsync(RScript.Mode.READ_WRITE, lua,
                RScript.ReturnType.MULTI, keys, (Object[]) args));
    }

    /**
     * 发出一次异步调用并在截止内等结果。截止已过不发；同步抛出的异常（客户端已关闭等）、异常完成、等超时、空回复，一律 {@link Deadline.DependencyException}。
     */
    private static <T> T await(String what, Deadline d, Supplier<? extends CompletionStage<T>> call) {
        return await(what, d, call, null);
    }

    /**
     * 同上，另带一个「放弃之后」的收尾动作。
     *
     * @param afterAbandoned 可为 null。只在<b>放弃等待时命令还在路上</b>（等到截止、或等待被中断）这一种失败上用到：等在途的调用有了结局
     *                       （成功或失败；Redisson 的重试到那时已经做完）之后调一次，在完成它的线程上（Redisson 的 I/O 线程）——必须不阻塞、不抛。
     *                       截止已过没有发出、发不出去、异常完成、空回复都不调：那几种失败发生时已经没有在途的命令
     */
    private static <T> T await(String what, Deadline d, Supplier<? extends CompletionStage<T>> call, Runnable afterAbandoned) {
        Objects.requireNonNull(d, "deadline");
        if (d.expired()) {
            throw new Deadline.DependencyException(what + " 之前预算已用完（没有发出）");
        }
        CompletionStage<T> stage;
        try {
            stage = call.get();
        } catch (RuntimeException e) {
            throw new Deadline.DependencyException(what + " 发不出去", e);
        }
        if (stage == null) {
            throw new Deadline.DependencyException(what + " 没有返回 future");
        }
        T reply;
        try {
            reply = d.await(stage, what);
        } catch (Deadline.DependencyException e) {
            if (afterAbandoned != null && !stage.toCompletableFuture().isDone()) {
                // 不取消在途的命令（它可能已经执行）；只在它有了结局之后收尾。此刻恰好完成的话回调当场执行，多做一次也无害
                stage.whenComplete((ignored, error) -> afterAbandoned.run());
            }
            throw e;
        }
        if (reply == null) {
            throw new Deadline.DependencyException(what + " 得到空回复");
        }
        return reply;
    }

    // ================================================================ 内部：解回复

    /** 回复里从 {@code offset} 起的三项（状态、a、pb）→ 一条落点记录。 */
    private static Record recordAt(long battleId, List<Object> reply, int offset, String what) {
        long state = integerAt(reply, offset, what);
        byte[] attempt = bytesAt(reply, offset + 1, what);
        byte[] pb = bytesAt(reply, offset + 2, what);
        if (state == SpectateScripts.RECORD_ABSENT) {
            return new Record.Absent();
        }
        if (state == SpectateScripts.RECORD_WRONG_TYPE) {
            return new Record.Corrupt("落点键不是 HASH battle_id=" + Long.toUnsignedString(battleId));
        }
        if (state != SpectateScripts.RECORD_PRESENT) {
            throw new Deadline.DependencyException(what + " 的回复不认识: 落点状态 " + state);
        }
        return switch (PlacementRecords.parse(battleId, attempt, pb)) {
            case PlacementRecords.Parsed.Ok ok -> new Record.Found(ok.placement());
            case PlacementRecords.Parsed.Corrupt corrupt -> new Record.Corrupt(corrupt.why() + " battle_id=" + Long.toUnsignedString(battleId));
        };
    }

    private static void requireSize(List<Object> reply, int expected, String what) {
        if (reply.size() != expected) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 应有 " + expected + " 项，实有 " + reply.size());
        }
    }

    private static long integerAt(List<Object> reply, int index, String what) {
        if (index >= reply.size() || !(reply.get(index) instanceof Long value)) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 第 " + index + " 项不是整数");
        }
        return value;
    }

    private static boolean flagAt(List<Object> reply, int index, String what) {
        long value = integerAt(reply, index, what);
        if (value != 0 && value != 1) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 第 " + index + " 项不是 0 / 1: " + value);
        }
        return value == 1;
    }

    private static byte[] bytesAt(List<Object> reply, int index, String what) {
        if (index >= reply.size() || !(reply.get(index) instanceof byte[] value)) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 第 " + index + " 项不是字节串");
        }
        return value;
    }

    /**
     * ZSET 的分数 → 毫秒。正常分数是整数毫秒（&lt; 2^53，Redis 原样打印）；脏分数向下取整（与整数分界比大小时，结论同 Redis 按 double 比的一致），
     * 正负无穷收到 {@code long} 的两端。
     */
    private static long scoreAt(List<Object> reply, int index, String what) {
        String text = new String(bytesAt(reply, index, what), StandardCharsets.US_ASCII);
        if (text.equals("inf") || text.equals("+inf")) {
            return Long.MAX_VALUE;
        }
        if (text.equals("-inf")) {
            return Long.MIN_VALUE;
        }
        double score;
        try {
            score = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 第 " + index + " 项不是分数: " + text);
        }
        if (Double.isNaN(score)) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 第 " + index + " 项不是分数: " + text);
        }
        return (long) Math.floor(score);
    }

    // ================================================================ 内部：小工具

    /** 抢标记的值：必须是编出来的整串。 */
    private static void requireNonEmptyMark(String markValue) {
        if (markValue == null || markValue.isEmpty()) {
            throw new IllegalArgumentException("标记值不能为空");
        }
    }

    /** 删标记的值：读到什么就传什么——脏标记可以是空串，只拒 null。 */
    private static void requireMark(String markValue) {
        if (markValue == null) {
            throw new IllegalArgumentException("标记值不能为 null");
        }
    }

    private static List<Long> distinct(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] markBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String markText(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    /**
     * 成员 ↔ 字节一一对应（见类注释）：本类读出来的成员每个字符都 ≤ 0xFF，原样还原成读到的字节。带更大码点的串不可能是本类读出来的
     * （调用方自己给的文本），按 UTF-8 发出，不让它被替换成问号去误摘别的成员。
     */
    private static byte[] memberBytes(String member) {
        for (int i = 0; i < member.length(); i++) {
            if (member.charAt(i) > 0xFF) {
                return member.getBytes(StandardCharsets.UTF_8);
            }
        }
        return member.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String memberText(byte[] member) {
        return new String(member, StandardCharsets.ISO_8859_1);
    }

    private static Throwable cause(Throwable e) {
        return e.getCause() == null ? e : e.getCause();
    }
}
