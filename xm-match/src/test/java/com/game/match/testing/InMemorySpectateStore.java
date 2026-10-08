package com.game.match.testing;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateRules;
import com.game.match.spectate.SpectateStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

/**
 * {@link SpectateStore} 的内存实现：163 / 164 / 开局钩子 / 清扫器的组件测试拿它当观战存储，不必连 Redis。语义逐条对着接口注释与规格 §4.3 的九段脚本写；
 * 它与 Redis 实现跑同一套契约测试 {@code SpectateStoreContract}——改这里的任何语义之前先改契约测试，两边一起过。
 *
 * <p><b>与另外两个内存存储共享状态</b>（真脚本同时碰票据、落点与观战键）：「有没有票据」读 {@link InMemoryTicketStore}（未过期的票），
 * 落点记录读写 {@link InMemoryPlacementStore}（{@code corrupt(id)} 标过的读成损坏；dead / stale 剔除经它的 {@code evict} 删记录），
 * 时间与标记的 TTL 用同一个 {@link ManualRedisClock}。把同一组实例交给被测对象的各个协作者即可。
 *
 * <pre>
 * ManualRedisClock clock = new ManualRedisClock();
 * InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
 * InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
 * InMemorySpectateStore spectate = new InMemorySpectateStore(clock, tickets, placements, events);   // events：与别的替身共用的事件序列（可省）
 * placements.put(placement);                              // 摆一条落点
 * spectate.putWatchable(77, clock.peekMs());              // 摆进可观战索引（成员 "77"）；putWatchable("abc", 1) 摆非法成员
 * spectate.putMark(1001, SpectateRules.encodeMark(77, SpectateRules.newNonce()));   // 摆一个旧标记；putMark(1001, "脏值") 摆脏标记
 * spectate.beforeAcquire = pid -> tickets.putTicket(pid, ticket, 60_000);            // 测试缝：入口检查之后、抢标记之前（基线 beforeAcquireWatchingHook）
 * spectate.faults.failNext("read");                       // 下一次 read 失败；"acquire:after" = 已写入但调用方看到异常（结局不明）
 * spectate.hang("evictAsync"); ... spectate.resume("evictAsync");                    // 挂起：见 {@link #hang}
 * assertThat(spectate.markOf(1001)).contains(value);
 * assertThat(spectate.watchable()).containsExactly("78", "77");                      // 分数降序
 * assertThat(events).containsSubsequence("spectate.acquire:1001", "observer.add:77:1001");   // 标记先于 AddObserver
 * </pre>
 * 故障注入的操作名 = 接口方法名；可变方法另有 {@code ":after"}（见 {@link Faults}）。截止已过时不执行、直接抛（同真实现）。
 * 两个 {@code *Async} 方法在调用线程上<b>当场生效</b>（确定性），除非被 {@link #hang} 挂起或注入了同名故障（那一次静默丢弃，记进 {@link #droppedAsync}）。
 * 线程安全（一把锁，临界区里不阻塞）。不模拟的：落点记录的 TTL、损坏记录的 attempt 字段（{@code Dead} 剔除遇到损坏记录一律不动）。
 */
public final class InMemorySpectateStore implements SpectateStore {

    /** 标记的寿命（同真实现的 PX 360 s）。 */
    public static final long MARK_TTL_MS = MatchBudgets.WATCHING_TTL_SECONDS * 1_000L;

    /** 故障注入。 */
    public final Faults faults = new Faults();
    /** 调用序列：每次调用一条，形如 {@code "entry(1001)"}、{@code "acquire(1001,77:0123456789abcdef)"}、{@code "evict(Missing[battleId=77])"}、{@code "list(20)"}。 */
    public final List<String> calls = new CopyOnWriteArrayList<>();
    /**
     * 事件序列（<b>生效了</b>的操作才记；与别的替身共用，断言先后次序用）：{@code "spectate.entry:<pid>"}、{@code "spectate.acquire:<pid>"}（写入或重放命中）、
     * {@code "spectate.release:<pid>"}（真的删了）、{@code "spectate.read:<battle>"}、{@code "spectate.evict:<member>"}（真的摘了）、
     * {@code "spectate.publish:<battle>"}（登记了）、{@code "spectate.sweep:<条数>"}。
     */
    public final List<String> events;
    /** 被注入的故障丢弃的异步操作（{@code "releaseAsync(…)"} / {@code "evictAsync(…)"}），按调用顺序。 */
    public final List<String> droppedAsync = new CopyOnWriteArrayList<>();
    /**
     * 测试缝：每次 {@link #acquire} 在原子判定<b>之前</b>调一次（入参是玩家号；不持锁，可以去动票据 / 标记 / 落点 / 索引）。
     * 对应基线的 {@code beforeAcquireWatchingHook}：模拟「入口检查之后才建出票据」「标记被并发请求抢走」「读落点之后记录被改写并公开」。
     */
    public volatile LongConsumer beforeAcquire;

    private final ManualRedisClock clock;
    private final InMemoryTicketStore tickets;
    private final InMemoryPlacementStore placements;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Mark> marks = new HashMap<>();
    private final Map<String, Long> index = new HashMap<>();
    private final Map<String, CountDownLatch> hung = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> parked = new HashMap<>();

    private record Mark(String value, long expiresAtMs) {
    }

    public InMemorySpectateStore(ManualRedisClock clock, InMemoryTicketStore tickets, InMemoryPlacementStore placements) {
        this(clock, tickets, placements, new CopyOnWriteArrayList<>());
    }

    /** @param events 与别的替身共用的事件序列 */
    public InMemorySpectateStore(ManualRedisClock clock, InMemoryTicketStore tickets, InMemoryPlacementStore placements, List<String> events) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 自带一套全新的时钟、票据与落点存储（只测观战存储自己时用；取它们见 {@link #clock()} / {@link #tickets()} / {@link #placements()}）。 */
    public InMemorySpectateStore() {
        this(new ManualRedisClock());
    }

    private InMemorySpectateStore(ManualRedisClock clock) {
        this(clock, new InMemoryTicketStore(clock), new InMemoryPlacementStore());
    }

    public ManualRedisClock clock() {
        return clock;
    }

    public InMemoryTicketStore tickets() {
        return tickets;
    }

    public InMemoryPlacementStore placements() {
        return placements;
    }

    // ================================================================ 测试侧：摆状态、看状态

    /** 直接放一个标记（覆盖已有的），寿命 360 s。值可以是任意串（脏标记）。 */
    public InMemorySpectateStore putMark(long playerId, String value) {
        return putMark(playerId, value, MARK_TTL_MS);
    }

    public InMemorySpectateStore putMark(long playerId, String value, long ttlMs) {
        lock.lock();
        try {
            marks.put(playerId, new Mark(Objects.requireNonNull(value, "value"), clock.peekMs() + ttlMs));
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** 玩家此刻的标记（未过期的；不计入 {@link #calls}，不触发故障注入）。 */
    public Optional<String> markOf(long playerId) {
        lock.lock();
        try {
            return Optional.ofNullable(liveMark(playerId)).map(Mark::value);
        } finally {
            lock.unlock();
        }
    }

    /** 标记剩余的 TTL（毫秒）；没有标记为 -1。 */
    public long markTtlMs(long playerId) {
        lock.lock();
        try {
            Mark mark = liveMark(playerId);
            return mark == null ? -1 : mark.expiresAtMs() - clock.peekMs();
        } finally {
            lock.unlock();
        }
    }

    /** 有标记的玩家数（未过期的）。 */
    public int markCount() {
        lock.lock();
        try {
            long now = clock.peekMs();
            return (int) marks.values().stream().filter(mark -> mark.expiresAtMs() > now).count();
        } finally {
            lock.unlock();
        }
    }

    /** 直接往索引里放一个成员（覆盖分数）。成员可以是非法串。 */
    public InMemorySpectateStore putWatchable(String member, long score) {
        lock.lock();
        try {
            index.put(Objects.requireNonNull(member, "member"), score);
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** 直接把一场战斗放进索引（成员是规范写法）。 */
    public InMemorySpectateStore putWatchable(long battleId, long score) {
        return putWatchable(SpectateRules.member(battleId), score);
    }

    /** 索引的全部成员，分数降序（同分按成员字符串降序）——即 {@link #list} 的次序。 */
    public List<String> watchable() {
        lock.lock();
        try {
            return descending().stream().map(Scored::member).toList();
        } finally {
            lock.unlock();
        }
    }

    public boolean isWatchable(long battleId) {
        lock.lock();
        try {
            return index.containsKey(SpectateRules.member(battleId));
        } finally {
            lock.unlock();
        }
    }

    /** 成员的分数；不在索引里为空。 */
    public OptionalLong scoreOf(String member) {
        lock.lock();
        try {
            Long score = index.get(member);
            return score == null ? OptionalLong.empty() : OptionalLong.of(score);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 让一个操作挂起（操作名 = 接口方法名）：之后对它的<b>同步</b>调用阻塞到 {@link #resume} 或各自的截止到点——到点抛
     * {@link Deadline.DependencyException}，<b>没有生效</b>（要「生效了但应答丢了」用 {@code faults.failNext("<op>:after")}）；
     * 对 {@code "releaseAsync"} / {@code "evictAsync"} 的调用<b>立刻返回</b>，效果攒到 {@link #resume} 时才生效。
     */
    public InMemorySpectateStore hang(String op) {
        hung.putIfAbsent(op, new CountDownLatch(1));
        return this;
    }

    /** 解除 {@link #hang}：先让攒着的异步效果按发出的次序生效，再放行阻塞着的同步调用。没挂起过也能调。 */
    public InMemorySpectateStore resume(String op) {
        CountDownLatch latch = hung.remove(op);
        List<Runnable> pending;
        lock.lock();
        try {
            pending = parked.remove(op);
        } finally {
            lock.unlock();
        }
        if (pending != null) {
            pending.forEach(Runnable::run);
        }
        if (latch != null) {
            latch.countDown();
        }
        return this;
    }

    // ================================================================ 观战标记

    @Override
    public Entry entry(long playerId, Deadline d) {
        calls.add("entry(" + Long.toUnsignedString(playerId) + ")");
        enter("entry", d);
        lock.lock();
        try {
            Mark mark = liveMark(playerId);
            events.add("spectate.entry:" + Long.toUnsignedString(playerId));
            return new Entry(hasTicket(playerId), Optional.ofNullable(mark).map(Mark::value));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Acquire acquire(long playerId, String markValue, Deadline d) {
        requireMarkValue(markValue);
        calls.add("acquire(" + Long.toUnsignedString(playerId) + "," + markValue + ")");
        if (d.expired()) {
            throw new Deadline.DependencyException("截止已过，没有发出命令: acquire");
        }
        LongConsumer hook = beforeAcquire;
        if (hook != null) {
            hook.accept(playerId);
        }
        enter("acquire", d);
        Acquire result;
        lock.lock();
        try {
            Mark mark = liveMark(playerId);
            if (hasTicket(playerId)) {
                result = Acquire.QUEUED;
            } else if (mark != null && mark.value().equals(markValue)) {
                result = Acquire.OK; // 重放：命中自己写下的值，不刷新 TTL
                events.add("spectate.acquire:" + Long.toUnsignedString(playerId));
            } else if (mark != null) {
                result = Acquire.BUSY;
            } else {
                marks.put(playerId, new Mark(markValue, clock.peekMs() + MARK_TTL_MS));
                result = Acquire.OK;
                events.add("spectate.acquire:" + Long.toUnsignedString(playerId));
            }
        } finally {
            lock.unlock();
        }
        faults.check("acquire:after");
        return result;
    }

    @Override
    public boolean release(long playerId, String markValue, Deadline d) {
        requireMarkValue(markValue);
        calls.add("release(" + Long.toUnsignedString(playerId) + "," + markValue + ")");
        enter("release", d);
        boolean removed = releaseNow(playerId, markValue);
        faults.check("release:after");
        return removed;
    }

    @Override
    public void releaseAsync(long playerId, String markValue) {
        requireMarkValue(markValue);
        String call = "releaseAsync(" + Long.toUnsignedString(playerId) + "," + markValue + ")";
        calls.add(call);
        async("releaseAsync", call, () -> releaseNow(playerId, markValue));
    }

    private boolean releaseNow(long playerId, String markValue) {
        lock.lock();
        try {
            Mark mark = liveMark(playerId);
            if (mark == null || !mark.value().equals(markValue)) {
                return false;
            }
            marks.remove(playerId);
            events.add("spectate.release:" + Long.toUnsignedString(playerId));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<Long, String> marksOf(List<Long> playerIds, Deadline d) {
        calls.add("marksOf(" + playerIds.stream().map(Long::toUnsignedString).toList() + ")");
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        enter("marksOf", d);
        lock.lock();
        try {
            Map<Long, String> out = new LinkedHashMap<>();
            for (long playerId : playerIds) {
                Mark mark = liveMark(playerId);
                if (mark != null) {
                    out.put(playerId, mark.value());
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ================================================================ 落点 + 索引

    @Override
    public Snapshot read(long battleId, Deadline d) {
        calls.add("read(" + Long.toUnsignedString(battleId) + ")");
        enter("read", d);
        lock.lock();
        try {
            events.add("spectate.read:" + Long.toUnsignedString(battleId));
            return new Snapshot(index.containsKey(SpectateRules.member(battleId)), recordOf(battleId), clock.peekMs());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Pick pickRandom(double r, Deadline d) {
        if (!(r >= 0.0 && r < 1.0)) {
            throw new IllegalArgumentException("r 必须在 [0, 1) 内: " + r);
        }
        calls.add("pickRandom(" + r + ")");
        enter("pickRandom", d);
        lock.lock();
        try {
            long now = clock.peekMs();
            long cutoff = SpectateRules.staleCutoff(now);
            List<Scored> alive = new ArrayList<>();
            index.forEach((member, score) -> {
                if (score >= cutoff) {
                    alive.add(new Scored(member, score));
                }
            });
            if (alive.isEmpty()) {
                return new Pick.None(now);
            }
            alive.sort(Comparator.comparingLong(Scored::score).thenComparing(Scored::member));
            Scored picked = alive.get((int) Math.min((long) Math.floor(r * alive.size()), alive.size() - 1L));
            return new Pick.Member(picked.member(), picked.score(), now);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean evict(Eviction e, Deadline d) {
        Objects.requireNonNull(e, "e");
        calls.add("evict(" + e + ")");
        enter("evict", d);
        boolean evicted = evictNow(e);
        faults.check("evict:after");
        return evicted;
    }

    @Override
    public void evictAsync(List<Eviction> batch) {
        List<Eviction> copy = List.copyOf(batch);
        String call = "evictAsync(" + copy + ")";
        calls.add(call);
        if (copy.isEmpty()) {
            return;
        }
        async("evictAsync", call, () -> copy.forEach(this::evictNow));
    }

    private boolean evictNow(Eviction e) {
        lock.lock();
        try {
            boolean evicted = switch (e) {
                case Eviction.Invalid invalid -> index.remove(invalid.member()) != null;
                case Eviction.Missing missing -> !placementExists(missing.battleId()) && index.remove(missing.member()) != null;
                case Eviction.Dead dead -> evictDead(dead);
                case Eviction.Stale stale -> evictStale(stale);
            };
            if (evicted) {
                events.add("spectate.evict:" + e.member());
            }
            return evicted;
        } finally {
            lock.unlock();
        }
    }

    /** 落点的 attempt 仍等于读到的那个（或落点已不在）才「删落点 + 摘成员」；损坏的记录无从比对，不动。 */
    private boolean evictDead(Eviction.Dead dead) {
        if (placements.corrupted(dead.battleId())) {
            return false;
        }
        Optional<BattlePlacement> stored = placements.stored(dead.battleId());
        if (stored.isPresent() && stored.get().getAttempt() != dead.attempt()) {
            return false;
        }
        boolean removedRecord = placements.evict(dead.battleId());
        boolean removedMember = index.remove(dead.member()) != null;
        return removedRecord || removedMember;
    }

    /** 成员的分数此刻仍小于分界才「删落点 + 摘成员」。 */
    private boolean evictStale(Eviction.Stale stale) {
        Long score = index.get(stale.member());
        if (score == null || score >= stale.cutoffMs()) {
            return false;
        }
        placements.evict(stale.battleId());
        index.remove(stale.member());
        return true;
    }

    @Override
    public boolean publish(BattlePlacement placement, Deadline d) {
        Objects.requireNonNull(placement, "placement");
        long battleId = placement.getBattleId();
        calls.add("publish(" + Long.toUnsignedString(battleId) + "#" + Integer.toUnsignedString(placement.getAttempt()) + ")");
        enter("publish", d);
        boolean published;
        lock.lock();
        try {
            Optional<BattlePlacement> stored = placements.stored(battleId);
            published = !placements.corrupted(battleId) && stored.isPresent() && stored.get().getAttempt() == placement.getAttempt();
            if (published) {
                index.put(SpectateRules.member(battleId), placement.getCreatedAtMs());
                events.add("spectate.publish:" + Long.toUnsignedString(battleId));
            }
        } finally {
            lock.unlock();
        }
        faults.check("publish:after");
        return published;
    }

    // ================================================================ 列表、清扫

    @Override
    public Listed list(int limit, Deadline d) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须 ≥ 1: " + limit);
        }
        calls.add("list(" + limit + ")");
        enter("list", d);
        lock.lock();
        try {
            List<Scored> all = descending();
            return new Listed(all.subList(0, Math.min(limit, all.size())), clock.peekMs());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<Long, Record> readPlacements(List<Long> battleIds, Deadline d) {
        calls.add("readPlacements(" + battleIds.stream().map(Long::toUnsignedString).toList() + ")");
        if (battleIds.isEmpty()) {
            return Map.of();
        }
        enter("readPlacements", d);
        lock.lock();
        try {
            Map<Long, Record> out = new LinkedHashMap<>();
            for (long battleId : battleIds) {
                out.computeIfAbsent(battleId, this::recordOf);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long sweep(Deadline d) {
        calls.add("sweep()");
        enter("sweep", d);
        long removed;
        lock.lock();
        try {
            long cutoff = SpectateRules.staleCutoff(clock.peekMs());
            int before = index.size();
            index.values().removeIf(score -> score < cutoff);
            removed = before - index.size();
            if (removed > 0) {
                events.add("spectate.sweep:" + removed);
            }
        } finally {
            lock.unlock();
        }
        faults.check("sweep:after");
        return removed;
    }

    @Override
    public long watchableCount(Deadline d) {
        calls.add("watchableCount()");
        enter("watchableCount", d);
        lock.lock();
        try {
            return index.size();
        } finally {
            lock.unlock();
        }
    }

    // ================================================================ 内部

    /** 同步操作的入口：截止已过不执行；被挂起就等到放行或截止；再看预置的故障。 */
    private void enter(String op, Deadline d) {
        Objects.requireNonNull(d, "d");
        if (d.expired()) {
            throw new Deadline.DependencyException("截止已过，没有发出命令: " + op);
        }
        CountDownLatch latch = hung.get(op);
        if (latch != null) {
            try {
                if (!latch.await(Math.max(1, d.remainingNanos()), TimeUnit.NANOSECONDS)) {
                    throw new Deadline.DependencyException(op + " 超过请求预算（注入的挂起，没有生效）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Deadline.DependencyException(op + " 被中断", e);
            }
        }
        faults.check(op);
    }

    /** 异步操作：被挂起就攒着；注入了故障就丢弃；否则当场生效。永不抛。 */
    private void async(String op, String call, Runnable effect) {
        if (hung.containsKey(op)) {
            lock.lock();
            try {
                parked.computeIfAbsent(op, k -> new ArrayList<>()).add(effect);
            } finally {
                lock.unlock();
            }
            return;
        }
        try {
            faults.check(op);
        } catch (RuntimeException e) {
            droppedAsync.add(call);
            return;
        }
        effect.run();
    }

    private static void requireMarkValue(String markValue) {
        if (markValue == null || markValue.isEmpty()) {
            throw new IllegalArgumentException("标记值不能为空");
        }
    }

    private boolean hasTicket(long playerId) {
        return tickets.ticketOf(playerId).isPresent();
    }

    private boolean placementExists(long battleId) {
        return placements.corrupted(battleId) || placements.stored(battleId).isPresent();
    }

    private Record recordOf(long battleId) {
        if (placements.corrupted(battleId)) {
            return new Record.Corrupt("落点记录损坏（测试标记的） battle_id=" + Long.toUnsignedString(battleId));
        }
        return placements.stored(battleId).<Record>map(Record.Found::new).orElseGet(Record.Absent::new);
    }

    /** 只在持锁时调。 */
    private Mark liveMark(long playerId) {
        Mark mark = marks.get(playerId);
        if (mark != null && mark.expiresAtMs() <= clock.peekMs()) {
            marks.remove(playerId);
            return null;
        }
        return mark;
    }

    /** 只在持锁时调：分数降序，同分按成员字符串降序。 */
    private List<Scored> descending() {
        List<Scored> all = new ArrayList<>(index.size());
        index.forEach((member, score) -> all.add(new Scored(member, score)));
        all.sort(Comparator.comparingLong(Scored::score).thenComparing(Scored::member).reversed());
        return all;
    }
}
