package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.rating.RatingReader;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link TicketStore} 的内存实现：各包的组件测试拿它当票据存储，不必等 Redis 实现。语义逐条对着接口注释写（不变量 I1–I5、每个写方法的 CAS 条件与
 * 重放行为）；真实现与它跑同一套契约测试，保证替身不漂移。
 *
 * <p>时间与 TTL 用 {@link ManualRedisClock}：票据到期后读不到（队列里的残留项照旧留着，同 Redis 的行为）。
 *
 * <pre>
 * ManualRedisClock clock = new ManualRedisClock();
 * InMemoryTicketStore store = new InMemoryTicketStore(clock);
 * store.enqueue(1001, "t-1001", new QueueRef(3, 0), 1, 150_000, 21_600_000, Deadline.after(1000));
 * store.putTicket(1002, ticket, 60_000);                 // 直接摆出任意状态（孤儿票、残留的 ready 票）
 * store.putQueueMember(queue, "abc", null);              // 非法成员 / 缺评分镜像
 * store.faults.failNext("pop:after");                    // 弹组已生效但调用方看到异常（结局不明）
 * assertThat(store.queueMembers(queue)).containsExactly("1001");
 * assertThat(store.calls).contains("enqueue(1001)");     // 调用序列
 * </pre>
 * 故障注入的操作名 = 接口方法名；写方法另有 {@code ":after"}（见 {@link Faults}）。线程安全（一把锁，临界区里不阻塞）。
 */
public final class InMemoryTicketStore implements TicketStore {

    /** 弹组重放标记的寿命（同真实现的 60 s）。 */
    public static final long POP_MARKER_TTL_MS = 60_000;

    /** 故障注入。 */
    public final Faults faults = new Faults();
    /** 调用序列：每次调用一条，形如 {@code "enqueue(1001)"}、{@code "pop(3:0,[1001, 1002])"}、{@code "snapshot(3:0)"}。 */
    public final List<String> calls = new CopyOnWriteArrayList<>();

    private final ManualRedisClock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Held> tickets = new HashMap<>();
    private final Map<String, List<String>> queues = new HashMap<>();
    private final Map<String, Map<String, Long>> ranks = new HashMap<>();
    private final Set<String> index = new LinkedHashSet<>();
    private final Map<String, Long> popMarkers = new HashMap<>();
    private final Map<String, Held> locks = new HashMap<>();

    /** 一条带到期时刻的值（票据，或凑单锁的持有者）。 */
    private static final class Held {
        Ticket ticket;
        String holder;
        long expiresAtMs;
    }

    public InMemoryTicketStore(ManualRedisClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InMemoryTicketStore() {
        this(new ManualRedisClock());
    }

    public ManualRedisClock clock() {
        return clock;
    }

    // ================================================================ 测试侧：摆状态、看状态

    /** 直接放一张票（覆盖已有的），不碰队列：用来摆出孤儿 queued 票、残留的 ready 票、状态不认识的票。 */
    public InMemoryTicketStore putTicket(long playerId, Ticket ticket, long ttlMs) {
        lock.lock();
        try {
            Held held = new Held();
            held.ticket = ticket;
            held.expiresAtMs = clock.peekMs() + ttlMs;
            tickets.put(playerId, held);
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** 直接往队尾放一个成员并登记注册集；{@code ratingCenti} 为 null 时不写评分镜像（摆出「镜像缺分」）。成员可以是非法串。 */
    public InMemoryTicketStore putQueueMember(QueueRef queue, String member, Long ratingCenti) {
        lock.lock();
        try {
            queues.computeIfAbsent(queue.queueKey(), k -> new ArrayList<>()).add(member);
            if (ratingCenti != null) {
                ranks.computeIfAbsent(queue.queueKey(), k -> new LinkedHashMap<>()).put(member, ratingCenti);
            }
            index.add(queue.queueKey());
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** 直接往注册集里放一个成员（摆出解析不了的队列键）。 */
    public InMemoryTicketStore putIndexMember(String member) {
        lock.lock();
        try {
            index.add(member);
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** 玩家此刻的票（不计入 {@link #calls}，不触发故障注入）。 */
    public Optional<Ticket> ticketOf(long playerId) {
        lock.lock();
        try {
            Held held = live(playerId);
            return held == null ? Optional.empty() : Optional.of(held.ticket);
        } finally {
            lock.unlock();
        }
    }

    /** 票据剩余的 TTL（毫秒）；没有票为 -1。 */
    public long ttlMs(long playerId) {
        lock.lock();
        try {
            Held held = live(playerId);
            return held == null ? -1 : held.expiresAtMs - clock.peekMs();
        } finally {
            lock.unlock();
        }
    }

    /** 队列成员，队首在前。 */
    public List<String> queueMembers(QueueRef queue) {
        lock.lock();
        try {
            return List.copyOf(queues.getOrDefault(queue.queueKey(), List.of()));
        } finally {
            lock.unlock();
        }
    }

    /** 评分镜像（成员 → centi）。 */
    public Map<String, Long> rankOf(QueueRef queue) {
        lock.lock();
        try {
            return Map.copyOf(ranks.getOrDefault(queue.queueKey(), Map.of()));
        } finally {
            lock.unlock();
        }
    }

    public boolean indexed(QueueRef queue) {
        lock.lock();
        try {
            return index.contains(queue.queueKey());
        } finally {
            lock.unlock();
        }
    }

    /** 凑单锁此刻的持有者；没人持有（或已过期）为空。 */
    public Optional<String> lockHolder(QueueRef queue) {
        lock.lock();
        try {
            Held held = locks.get(queue.lockKey());
            return held == null || held.expiresAtMs <= clock.peekMs() ? Optional.empty() : Optional.of(held.holder);
        } finally {
            lock.unlock();
        }
    }

    /** 有票的玩家数（未过期的）。 */
    public int ticketCount() {
        lock.lock();
        try {
            long now = clock.peekMs();
            return (int) tickets.values().stream().filter(h -> h.expiresAtMs > now).count();
        } finally {
            lock.unlock();
        }
    }

    // ================================================================ TicketReader

    @Override
    public Optional<Ticket> read(long playerId, Deadline d) {
        return run("read", "read(" + id(playerId) + ")", false, () -> {
            Held held = live(playerId);
            return held == null ? Optional.<Ticket>empty() : Optional.of(held.ticket);
        });
    }

    @Override
    public Map<Long, Ticket> readAll(Collection<Long> playerIds, Deadline d) {
        return run("readAll", "readAll(" + ids(playerIds) + ")", false, () -> {
            Map<Long, Ticket> out = new LinkedHashMap<>();
            for (Long playerId : playerIds) {
                Held held = live(playerId);
                if (held != null) {
                    out.put(playerId, held.ticket);
                }
            }
            return out;
        });
    }

    @Override
    public Status status(long playerId, Deadline d) {
        return run("status", "status(" + id(playerId) + ")", false, () -> {
            Held held = live(playerId);
            return new Status(held == null ? Optional.empty() : Optional.of(held.ticket), clock.peekMs());
        });
    }

    // ================================================================ 自愈、建票

    @Override
    public boolean heal(long playerId, Ticket seen, HealMode mode, Deadline d) {
        Objects.requireNonNull(seen, "seen");
        return run("heal", "heal(" + id(playerId) + "," + mode + ")", true, () -> {
            Held held = live(playerId);
            if (held == null) {
                return true;
            }
            Ticket now = held.ticket;
            if (!now.ticketId().equals(seen.ticketId())) {
                return false;
            }
            boolean heal;
            if (mode == HealMode.READY) {
                heal = now.state() == TicketState.READY;
            } else {
                heal = now.state() == TicketState.QUEUED && now.queueKey().equals(seen.queueKey())
                        && !queues.getOrDefault(now.queueKey(), List.of()).contains(id(playerId));
            }
            if (heal) {
                tickets.remove(playerId);
            }
            return heal;
        });
    }

    @Override
    public JoinResult enqueue(long playerId, String ticketId, QueueRef queue, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
        TicketRef ref = new TicketRef(playerId, ticketId);
        requireTtl(ttlMs);
        return run("enqueue", "enqueue(" + id(playerId) + ")", true, () -> {
            JoinResult existing = existing(ref);
            if (existing != null) {
                return existing;
            }
            long now = clock.peekMs();
            String key = queue.queueKey();
            store(playerId, new Ticket(ticketId, queue.mode(), queue.configId(), TicketState.QUEUED, now, zoneId, key, ratingCenti, 0, 0, 0), ttlMs);
            index.add(key);
            ranks.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id(playerId), ratingCenti);
            queues.computeIfAbsent(key, k -> new ArrayList<>()).add(id(playerId));
            return new JoinResult.Created(now);
        });
    }

    @Override
    public JoinResult createMatched(long playerId, String ticketId, int mode, int configId, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
        TicketRef ref = new TicketRef(playerId, ticketId);
        requireTtl(ttlMs);
        return run("createMatched", "createMatched(" + id(playerId) + ")", true, () -> {
            JoinResult existing = existing(ref);
            if (existing != null) {
                return existing;
            }
            long now = clock.peekMs();
            store(playerId, new Ticket(ticketId, mode, configId, TicketState.MATCHED, now, zoneId, "", ratingCenti, 0, 0, 0), ttlMs);
            return new JoinResult.Created(now);
        });
    }

    @Override
    public OptionalLong createGroup(List<GroupMember> members, int mode, int configId, long teamId, long ttlMs, Deadline d) {
        requireTtl(ttlMs);
        Set<Long> seen = new HashSet<>();
        for (GroupMember member : members) {
            if (!seen.add(member.playerId())) {
                throw new IllegalArgumentException("整组建票的玩家号重复: " + id(member.playerId()));
            }
        }
        return run("createGroup", "createGroup(" + ids(members.stream().map(GroupMember::playerId).toList()) + ")", true, () -> {
            for (GroupMember member : members) {
                Held held = live(member.playerId());
                if (held != null && !held.ticket.ticketId().equals(member.ticketId())) {
                    return OptionalLong.of(member.playerId());
                }
            }
            long now = clock.peekMs();
            for (GroupMember member : members) {
                if (live(member.playerId()) == null) {
                    store(member.playerId(), new Ticket(member.ticketId(), mode, configId, TicketState.MATCHED, now, member.zoneId(), "",
                            RatingReader.DEFAULT_CENTI, teamId, 0, 0), ttlMs);
                }
            }
            return OptionalLong.empty();
        });
    }

    // ================================================================ 取消与凑单

    @Override
    public boolean cancel(long playerId, String ticketId, QueueRef queue, Deadline d) {
        TicketRef ref = new TicketRef(playerId, ticketId);
        return run("cancel", "cancel(" + id(playerId) + ")", true, () -> {
            Held held = live(playerId);
            if (held == null || !held.ticket.ticketId().equals(ref.ticketId()) || held.ticket.state() != TicketState.QUEUED
                    || !held.ticket.queueKey().equals(queue.queueKey())) {
                return false;
            }
            tickets.remove(playerId);
            removeFromQueue(queue.queueKey(), id(playerId));
            return true;
        });
    }

    @Override
    public QueueSnapshot snapshot(QueueRef queue, int limit, Deadline d) {
        if (limit < 1) {
            throw new IllegalArgumentException("快照长度必须 ≥ 1: " + limit);
        }
        return run("snapshot", "snapshot(" + queue + ")", false, () -> {
            List<String> members = queues.getOrDefault(queue.queueKey(), List.of());
            Map<String, Long> rank = ranks.getOrDefault(queue.queueKey(), Map.of());
            List<SnapshotEntry> entries = new ArrayList<>();
            for (int i = 0; i < members.size() && i < limit; i++) {
                String member = members.get(i);
                Long score = rank.get(member);
                entries.add(new SnapshotEntry(member, parsePlayerId(member), score == null ? OptionalLong.empty() : OptionalLong.of(score)));
            }
            return new QueueSnapshot(entries, clock.peekMs());
        });
    }

    @Override
    public boolean drop(QueueRef queue, long playerId, DropReason reason, String seenTicketId, Deadline d) {
        Objects.requireNonNull(reason, "reason");
        return run("drop", "drop(" + queue + "," + id(playerId) + "," + reason + ")", true, () -> {
            Held held = live(playerId);
            boolean validQueued = held != null && held.ticket.state() == TicketState.QUEUED && held.ticket.queueKey().equals(queue.queueKey());
            if (!validQueued) {
                removeFromQueue(queue.queueKey(), id(playerId));
                return true;
            }
            if (reason != DropReason.INVALID && held.ticket.ticketId().equals(seenTicketId)) {
                tickets.remove(playerId);
                removeFromQueue(queue.queueKey(), id(playerId));
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean dropMalformed(QueueRef queue, String member, Deadline d) {
        Objects.requireNonNull(member, "member");
        return run("dropMalformed", "dropMalformed(" + queue + "," + member + ")", true, () -> removeFromQueue(queue.queueKey(), member));
    }

    @Override
    public PopResult pop(QueueRef queue, String popToken, List<TicketRef> members, long matchedTtlMs, Deadline d) {
        requireTtl(matchedTtlMs);
        if (popToken == null || popToken.isEmpty() || members.isEmpty()) {
            throw new IllegalArgumentException("弹组必须带 token 与至少一名成员");
        }
        Set<Long> seen = new HashSet<>();
        for (TicketRef member : members) {
            if (!seen.add(member.playerId())) {
                throw new IllegalArgumentException("弹组的玩家号重复: " + id(member.playerId()));
            }
        }
        return run("pop", "pop(" + queue + "," + ids(members.stream().map(TicketRef::playerId).toList()) + ")", true, () -> {
            long now = clock.peekMs();
            Long marker = popMarkers.get(popToken);
            if (marker != null && marker > now) {
                return new PopResult.Replayed();
            }
            String key = queue.queueKey();
            List<String> list = queues.getOrDefault(key, List.of());
            List<Long> invalid = new ArrayList<>();
            for (TicketRef member : members) {
                Held held = live(member.playerId());
                boolean ok = held != null && held.ticket.ticketId().equals(member.ticketId()) && held.ticket.state() == TicketState.QUEUED
                        && held.ticket.queueKey().equals(key) && !held.ticket.backingOff(now) && list.contains(id(member.playerId()));
                if (!ok) {
                    invalid.add(member.playerId());
                }
            }
            if (!invalid.isEmpty()) {
                return new PopResult.Invalid(invalid);
            }
            for (TicketRef member : members) {
                removeFromQueue(key, id(member.playerId()));
                Held held = tickets.get(member.playerId());
                held.ticket = with(held.ticket, TicketState.MATCHED, held.ticket.battleId(), held.ticket.notBeforeMs());
                held.expiresAtMs = now + matchedTtlMs;
            }
            popMarkers.put(popToken, now + POP_MARKER_TTL_MS);
            return new PopResult.Popped();
        });
    }

    // ================================================================ gather 的票据写

    @Override
    public boolean markReady(TicketRef ticket, long battleId, long readyTtlMs, Deadline d) {
        requireTtl(readyTtlMs);
        if (battleId == 0) {
            throw new IllegalArgumentException("ready 票必须带 battle_id");
        }
        return run("markReady", "markReady(" + id(ticket.playerId()) + ")", true, () -> {
            Held held = live(ticket.playerId());
            if (held == null || !held.ticket.ticketId().equals(ticket.ticketId())) {
                return false;
            }
            if (held.ticket.state() == TicketState.READY) {
                return held.ticket.battleId() == battleId;
            }
            if (held.ticket.state() != TicketState.MATCHED) {
                return false;
            }
            held.ticket = with(held.ticket, TicketState.READY, battleId, held.ticket.notBeforeMs());
            held.expiresAtMs = clock.peekMs() + readyTtlMs;
            return true;
        });
    }

    @Override
    public int extendMatched(List<TicketRef> refs, long ttlMs, Deadline d) {
        requireTtl(ttlMs);
        return run("extendMatched", "extendMatched(" + ids(refs.stream().map(TicketRef::playerId).toList()) + ")", true, () -> {
            int extended = 0;
            for (TicketRef ref : refs) {
                Held held = live(ref.playerId());
                if (held != null && held.ticket.ticketId().equals(ref.ticketId()) && held.ticket.state() == TicketState.MATCHED) {
                    held.expiresAtMs = clock.peekMs() + ttlMs;
                    extended++;
                }
            }
            return extended;
        });
    }

    @Override
    public boolean delete(TicketRef ticket, Deadline d) {
        return run("delete", "delete(" + id(ticket.playerId()) + ")", true, () -> deleteIfSame(ticket));
    }

    @Override
    public int deleteGroup(List<TicketRef> refs, Deadline d) {
        return run("deleteGroup", "deleteGroup(" + ids(refs.stream().map(TicketRef::playerId).toList()) + ")", true, () -> {
            int deleted = 0;
            for (TicketRef ref : refs) {
                if (deleteIfSame(ref)) {
                    deleted++;
                }
            }
            return deleted;
        });
    }

    @Override
    public int requeueFront(QueueRef queue, List<TicketRef> survivorsInOrder, long queuedTtlMs, long notBeforeDelayMs, Deadline d) {
        requireTtl(queuedTtlMs);
        if (notBeforeDelayMs < 0) {
            throw new IllegalArgumentException("退避时长不能为负: " + notBeforeDelayMs);
        }
        return run("requeueFront", "requeueFront(" + queue + "," + ids(survivorsInOrder.stream().map(TicketRef::playerId).toList()) + ")", true, () -> {
            long now = clock.peekMs();
            String key = queue.queueKey();
            int requeued = 0;
            for (int i = survivorsInOrder.size() - 1; i >= 0; i--) {
                TicketRef ref = survivorsInOrder.get(i);
                Held held = live(ref.playerId());
                if (held == null || !held.ticket.ticketId().equals(ref.ticketId()) || held.ticket.state() != TicketState.MATCHED
                        || !held.ticket.queueKey().equals(key)) {
                    continue;
                }
                held.ticket = with(held.ticket, TicketState.QUEUED, 0, notBeforeDelayMs > 0 ? now + notBeforeDelayMs : 0);
                held.expiresAtMs = now + queuedTtlMs;
                queues.computeIfAbsent(key, k -> new ArrayList<>()).add(0, id(ref.playerId()));
                ranks.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id(ref.playerId()), held.ticket.ratingCenti());
                requeued++;
            }
            if (requeued > 0) {
                index.add(key);
            }
            return requeued;
        });
    }

    // ================================================================ 注册集、深度、凑单锁

    @Override
    public Set<String> queueIndex(Deadline d) {
        return run("queueIndex", "queueIndex()", false, () -> new LinkedHashSet<>(index));
    }

    @Override
    public long queueLength(QueueRef queue, Deadline d) {
        return run("queueLength", "queueLength(" + queue + ")", false, () -> (long) queues.getOrDefault(queue.queueKey(), List.of()).size());
    }

    @Override
    public boolean pruneIfEmpty(QueueRef queue, Deadline d) {
        return run("pruneIfEmpty", "pruneIfEmpty(" + queue + ")", true, () -> {
            String key = queue.queueKey();
            if (!queues.getOrDefault(key, List.of()).isEmpty()) {
                return false;
            }
            queues.remove(key);
            ranks.remove(key);
            index.remove(key);
            return true;
        });
    }

    @Override
    public boolean tryLockQueue(QueueRef queue, String instanceId, long ttlMs, Deadline d) {
        requireTtl(ttlMs);
        Objects.requireNonNull(instanceId, "instanceId");
        return run("tryLockQueue", "tryLockQueue(" + queue + ")", true, () -> {
            long now = clock.peekMs();
            Held held = locks.get(queue.lockKey());
            if (held != null && held.expiresAtMs > now) {
                return false;
            }
            Held mine = new Held();
            mine.holder = instanceId;
            mine.expiresAtMs = now + ttlMs;
            locks.put(queue.lockKey(), mine);
            return true;
        });
    }

    @Override
    public void unlockQueue(QueueRef queue, String instanceId, Deadline d) {
        run("unlockQueue", "unlockQueue(" + queue + ")", true, () -> {
            Held held = locks.get(queue.lockKey());
            if (held != null && held.holder.equals(instanceId)) {
                locks.remove(queue.lockKey());
            }
            return null;
        });
    }

    // ================================================================ 内部

    private interface Op<T> {
        T apply();
    }

    /** 记调用 → 执行前的注入点 → 持锁执行 → 写方法执行后的注入点。 */
    private <T> T run(String op, String call, boolean write, Op<T> body) {
        calls.add(call);
        faults.check(op);
        T result;
        lock.lock();
        try {
            result = body.apply();
        } finally {
            lock.unlock();
        }
        if (write) {
            faults.check(op + ":after");
        }
        return result;
    }

    /** 未过期的票；过期的顺手清掉。调用方已持锁。 */
    private Held live(long playerId) {
        Held held = tickets.get(playerId);
        if (held != null && held.expiresAtMs <= clock.peekMs()) {
            tickets.remove(playerId);
            return null;
        }
        return held;
    }

    private void store(long playerId, Ticket ticket, long ttlMs) {
        Held held = new Held();
        held.ticket = ticket;
        held.expiresAtMs = clock.peekMs() + ttlMs;
        tickets.put(playerId, held);
    }

    private JoinResult existing(TicketRef ref) {
        Held held = live(ref.playerId());
        if (held == null) {
            return null;
        }
        return held.ticket.ticketId().equals(ref.ticketId()) ? new JoinResult.Replayed() : new JoinResult.Exists(held.ticket.ticketId());
    }

    private boolean deleteIfSame(TicketRef ref) {
        Held held = live(ref.playerId());
        if (held == null || !held.ticket.ticketId().equals(ref.ticketId())) {
            return false;
        }
        tickets.remove(ref.playerId());
        return true;
    }

    /** 摘掉队列里全部等于 {@code member} 的项，并摘评分镜像；返回队列里是否确实摘掉了至少一项。 */
    private boolean removeFromQueue(String queueKey, String member) {
        Map<String, Long> rank = ranks.get(queueKey);
        if (rank != null) {
            rank.remove(member);
        }
        List<String> list = queues.get(queueKey);
        return list != null && list.removeIf(member::equals);
    }

    private static Ticket with(Ticket t, TicketState state, long battleId, long notBeforeMs) {
        return new Ticket(t.ticketId(), t.mode(), t.configId(), state, t.enqueuedAtMs(), t.zoneId(), t.queueKey(), t.ratingCenti(), t.teamId(),
                battleId, notBeforeMs);
    }

    /** 队列成员 → 玩家号：只认不带前导零的非 0 无符号十进制，其余为 0（非法成员）。 */
    static long parsePlayerId(String member) {
        if (member == null || member.isEmpty() || member.length() > 20 || member.charAt(0) == '0') {
            return 0;
        }
        for (int i = 0; i < member.length(); i++) {
            if (member.charAt(i) < '0' || member.charAt(i) > '9') {
                return 0;
            }
        }
        try {
            return Long.parseUnsignedLong(member);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void requireTtl(long ttlMs) {
        if (ttlMs < 1) {
            throw new IllegalArgumentException("TTL 必须 ≥ 1 ms: " + ttlMs);
        }
    }

    private static String id(long playerId) {
        return Long.toUnsignedString(playerId);
    }

    private static String ids(Collection<Long> playerIds) {
        List<String> out = new ArrayList<>(playerIds.size());
        for (Long playerId : playerIds) {
            out.add(id(playerId));
        }
        return out.toString();
    }
}
