package com.game.match.ticket;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.rating.RatingReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link TicketStore} 的生产实现：每个方法一段 Lua（{@link TicketScripts}），全部键经 {@link RedisKeys} 生成、同在 {@code {match}} 一个槽。
 *
 * <ul>
 *   <li><b>阻塞但不占锁</b>：用 Redisson 的异步 API 发出，再在调用线程上等到截止（{@link Deadline#await}）——不持 {@code synchronized}，
 *       可以在 gather 的虚拟线程上调。</li>
 *   <li><b>失败一律 {@link Deadline.DependencyException}</b>：Redis 报错、等过了截止、回复形状不对、票据 HASH 损坏。截止在发出之前就已经用完时
 *       <b>不发</b>（什么都没写），照样抛它；等超时的那一种，命令还在路上、可能随后执行——对写方法这就是「结局不明」。</li>
 *   <li><b>单条 {@code evalAsync}，不用 RBatch</b>；编解码 {@link StringCodec}（键、参数、票据字段全是 ASCII 文本，回复里整数是 {@code Long}、
 *       字符串是 {@code String}）；全部脚本以 {@link RScript.Mode#READ_WRITE} 执行（读主库，理由见 {@link TicketScripts}）。</li>
 *   <li>三个不走 Lua 的读写：注册集 {@code SMEMBERS}、队列 {@code LLEN}、抢凑单锁 {@code SET NX PX}。前两个是咨询性的读（凑单每轮都读，
 *       读到旧值只是晚一轮）；抢锁被重发时第二次会抢不到自己刚抢到的锁——只是白等一个锁 TTL，锁本来就只是效率手段。</li>
 * </ul>
 * 入参校验（玩家号非 0、票号非空、TTL ≥ 1 ms、名单不重复）在发出之前做，违反是调用方的 bug。线程安全，无本地状态。
 */
public final class RedissonTicketStore implements TicketStore {

    /** 弹组重放标记的寿命：远大于 Redisson 一次重发的窗口（最坏 4.2 s），又不至于让标记堆积。 */
    public static final long POP_MARKER_TTL_MS = 60_000;
    /** 回队首重放标记的寿命（理由同上）。 */
    public static final long REQUEUE_MARKER_TTL_MS = POP_MARKER_TTL_MS;

    private static final String REASON_INVALID = "invalid";
    private static final String REASON_IN_BATTLE = "in_battle";
    private static final String REASON_OFFLINE = "offline";
    private static final String HEAL_READY = "ready";
    private static final String HEAL_ORPHAN = "orphan";

    private final RedissonClient redis;

    public RedissonTicketStore(RedissonClient redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    // ================================================================ TicketReader

    @Override
    public Optional<Ticket> read(long playerId, Deadline d) {
        return status(playerId, d).ticket();
    }

    @Override
    public Map<Long, Ticket> readAll(Collection<Long> playerIds, Deadline d) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(playerIds));
        Map<Long, Ticket> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<Object> keys = new ArrayList<>(ids.size());
        for (long playerId : ids) {
            keys.add(ticketKey(playerId));
        }
        List<Object> reply = evalList("批量读票据", TicketScripts.STATUS, keys, d);
        int cursor = 1;
        for (long playerId : ids) {
            int fields = (int) integerAt(reply, cursor, "批量读票据");
            List<Object> flat = slice(reply, cursor + 1, fields, "批量读票据");
            cursor += 1 + fields;
            TicketCodec.decode(flat, playerId).ifPresent(ticket -> out.put(playerId, ticket));
        }
        return out;
    }

    @Override
    public Status status(long playerId, Deadline d) {
        List<Object> reply = evalList("读票据", TicketScripts.STATUS, List.of(ticketKey(playerId)), d);
        long now = integerAt(reply, 0, "读票据");
        int fields = (int) integerAt(reply, 1, "读票据");
        return new Status(TicketCodec.decode(slice(reply, 2, fields, "读票据"), playerId), now);
    }

    // ================================================================ 自愈、建票

    @Override
    public boolean heal(long playerId, Ticket seen, HealMode mode, Deadline d) {
        Objects.requireNonNull(seen, "seen");
        Objects.requireNonNull(mode, "mode");
        List<Object> keys = new ArrayList<>(2);
        keys.add(ticketKey(playerId));
        if (mode == HealMode.ORPHAN && QueueRef.ofQueueKey(seen.queueKey()).isPresent()) {
            keys.add(seen.queueKey());
        }
        return evalInteger("自愈残留票", TicketScripts.HEAL, keys, d, seen.ticketId(), mode == HealMode.READY ? HEAL_READY : HEAL_ORPHAN,
                TicketCodec.member(playerId), seen.queueKey()) == 1;
    }

    @Override
    public JoinResult enqueue(long playerId, String ticketId, QueueRef queue, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
        new TicketRef(playerId, ticketId);
        Objects.requireNonNull(queue, "queue");
        requireTtl(ttlMs);
        List<Object> keys = List.of(ticketKey(playerId), RedisKeys.matchQueueIndex(), queue.queueKey(), queue.rankKey());
        return join("入队建票", keys, d, ticketId, queue.mode(), queue.configId(), zoneId, ratingCenti, ttlMs, playerId);
    }

    @Override
    public JoinResult createMatched(long playerId, String ticketId, int mode, int configId, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
        new TicketRef(playerId, ticketId);
        requireTtl(ttlMs);
        return join("建 matched 票", List.of(ticketKey(playerId)), d, ticketId, mode, configId, zoneId, ratingCenti, ttlMs, playerId);
    }

    private JoinResult join(String what, List<Object> keys, Deadline d, String ticketId, int mode, int configId, int zoneId, long ratingCenti,
                            long ttlMs, long playerId) {
        List<Object> reply = evalList(what, TicketScripts.JOIN, keys, d, ticketId, Integer.toString(mode), Integer.toUnsignedString(configId),
                Integer.toUnsignedString(zoneId), Long.toString(ratingCenti), Long.toString(ttlMs), TicketCodec.member(playerId));
        long code = integerAt(reply, 0, what);
        String detail = textAt(reply, 1, what);
        if (code == 0) {
            try {
                return new JoinResult.Created(Long.parseLong(detail));
            } catch (NumberFormatException e) {
                throw new Deadline.DependencyException(what + " 回复里的入队时刻不是数字: " + detail);
            }
        }
        if (code == 1) {
            return new JoinResult.Replayed();
        }
        if (code == 2 && !detail.isEmpty()) {
            return new JoinResult.Exists(detail);
        }
        if (code == 3) {
            throw new Deadline.DependencyException("票据 HASH 损坏：存在但没有票号 player=" + TicketCodec.member(playerId));
        }
        throw new Deadline.DependencyException(what + " 的回复不认识: " + reply);
    }

    @Override
    public OptionalLong createGroup(List<GroupMember> members, int mode, int configId, long teamId, long ttlMs, Deadline d) {
        requireTtl(ttlMs);
        requireDistinct(members.stream().map(GroupMember::playerId).toList(), "整组建票");
        if (members.isEmpty()) {
            return OptionalLong.empty();
        }
        List<Object> keys = new ArrayList<>(members.size());
        List<Object> args = new ArrayList<>(5 + 2 * members.size());
        args.add(Integer.toString(mode));
        args.add(Integer.toUnsignedString(configId));
        args.add(Long.toUnsignedString(teamId));
        args.add(Long.toString(ttlMs));
        args.add(Long.toString(RatingReader.DEFAULT_CENTI));
        for (GroupMember member : members) {
            keys.add(ticketKey(member.playerId()));
            args.add(member.ticketId());
            args.add(Integer.toUnsignedString(member.zoneId()));
        }
        long conflict = evalInteger("整组建票", TicketScripts.CREATE_GROUP, keys, d, args.toArray());
        if (conflict == 0) {
            return OptionalLong.empty();
        }
        if (conflict < 1 || conflict > members.size()) {
            throw new Deadline.DependencyException("整组建票的回复越界: " + conflict);
        }
        return OptionalLong.of(members.get((int) conflict - 1).playerId());
    }

    // ================================================================ 取消与凑单

    @Override
    public boolean cancel(long playerId, String ticketId, QueueRef queue, Deadline d) {
        new TicketRef(playerId, ticketId);
        Objects.requireNonNull(queue, "queue");
        return evalInteger("取消排队", TicketScripts.CANCEL, List.of(ticketKey(playerId), queue.queueKey(), queue.rankKey()), d, ticketId,
                TicketCodec.member(playerId)) == 1;
    }

    @Override
    public QueueSnapshot snapshot(QueueRef queue, int limit, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        if (limit < 1) {
            throw new IllegalArgumentException("快照长度必须 ≥ 1: " + limit);
        }
        List<Object> reply = evalList("读队列快照", TicketScripts.SNAPSHOT, List.of(queue.queueKey(), queue.rankKey()), d, Integer.toString(limit));
        long now = integerAt(reply, 0, "读队列快照");
        if (reply.size() % 2 != 1) {
            throw new Deadline.DependencyException("队列快照的回复不成对: size=" + reply.size());
        }
        List<SnapshotEntry> entries = new ArrayList<>((reply.size() - 1) / 2);
        for (int i = 1; i < reply.size(); i += 2) {
            String member = textAt(reply, i, "读队列快照");
            entries.add(new SnapshotEntry(member, TicketCodec.parseMember(member), TicketCodec.parseScore(textAt(reply, i + 1, "读队列快照"))));
        }
        return new QueueSnapshot(entries, now);
    }

    @Override
    public boolean drop(QueueRef queue, long playerId, DropReason reason, String seenTicketId, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(reason, "reason");
        requirePlayer(playerId);
        String wire = switch (reason) {
            case INVALID -> REASON_INVALID;
            case IN_BATTLE -> REASON_IN_BATTLE;
            case OFFLINE -> REASON_OFFLINE;
        };
        return evalInteger("剔出队列", TicketScripts.DROP, List.of(queue.queueKey(), queue.rankKey(), ticketKey(playerId)), d,
                TicketCodec.member(playerId), wire, seenTicketId == null ? "" : seenTicketId) == 1;
    }

    @Override
    public boolean dropMalformed(QueueRef queue, String member, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(member, "member");
        return evalInteger("剔除非法成员", TicketScripts.DROP, List.of(queue.queueKey(), queue.rankKey()), d, member, REASON_INVALID, "") == 1;
    }

    @Override
    public PopResult pop(QueueRef queue, String popToken, List<TicketRef> members, long matchedTtlMs, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        requireTtl(matchedTtlMs);
        if (popToken == null || popToken.isEmpty() || members.isEmpty()) {
            throw new IllegalArgumentException("弹组必须带 token 与至少一名成员");
        }
        requireDistinct(members.stream().map(TicketRef::playerId).toList(), "弹组");
        List<Object> keys = new ArrayList<>(3 + members.size());
        keys.add(queue.queueKey());
        keys.add(queue.rankKey());
        keys.add(RedisKeys.matchPopMarker(popToken));
        List<Object> args = new ArrayList<>(2 + 2 * members.size());
        args.add(Long.toString(matchedTtlMs));
        args.add(Long.toString(POP_MARKER_TTL_MS));
        for (TicketRef member : members) {
            keys.add(ticketKey(member.playerId()));
            args.add(TicketCodec.member(member.playerId()));
            args.add(member.ticketId());
        }
        List<Object> reply = evalList("弹组", TicketScripts.POP, keys, d, args.toArray());
        long code = integerAt(reply, 0, "弹组");
        if (code == 0) {
            return new PopResult.Popped();
        }
        if (code == 1) {
            return new PopResult.Replayed();
        }
        if (code != 2 || reply.size() < 2) {
            throw new Deadline.DependencyException("弹组的回复不认识: " + reply);
        }
        List<Long> invalid = new ArrayList<>(reply.size() - 1);
        for (int i = 1; i < reply.size(); i++) {
            long index = integerAt(reply, i, "弹组");
            if (index < 1 || index > members.size()) {
                throw new Deadline.DependencyException("弹组的回复越界: " + reply);
            }
            invalid.add(members.get((int) index - 1).playerId());
        }
        return new PopResult.Invalid(invalid);
    }

    // ================================================================ gather 的票据写

    @Override
    public boolean markReady(TicketRef ticket, long battleId, long readyTtlMs, Deadline d) {
        Objects.requireNonNull(ticket, "ticket");
        requireTtl(readyTtlMs);
        if (battleId == 0) {
            throw new IllegalArgumentException("ready 票必须带 battle_id");
        }
        return evalInteger("置 ready", TicketScripts.READY, List.of(ticketKey(ticket.playerId())), d, ticket.ticketId(),
                Long.toUnsignedString(battleId), Long.toString(readyTtlMs)) == 1;
    }

    @Override
    public int extendMatched(List<TicketRef> tickets, long ttlMs, Deadline d) {
        requireTtl(ttlMs);
        if (tickets.isEmpty()) {
            return 0;
        }
        List<Object> keys = new ArrayList<>(tickets.size());
        List<Object> args = new ArrayList<>(1 + tickets.size());
        args.add(Long.toString(ttlMs));
        for (TicketRef ticket : tickets) {
            keys.add(ticketKey(ticket.playerId()));
            args.add(ticket.ticketId());
        }
        return (int) evalInteger("补偿前续期", TicketScripts.EXTEND, keys, d, args.toArray());
    }

    @Override
    public boolean delete(TicketRef ticket, Deadline d) {
        Objects.requireNonNull(ticket, "ticket");
        return evalInteger("删票", TicketScripts.DEL, List.of(ticketKey(ticket.playerId())), d, ticket.ticketId()) == 1;
    }

    @Override
    public int deleteGroup(List<TicketRef> tickets, Deadline d) {
        if (tickets.isEmpty()) {
            return 0;
        }
        List<Object> keys = new ArrayList<>(tickets.size());
        List<Object> args = new ArrayList<>(tickets.size());
        for (TicketRef ticket : tickets) {
            keys.add(ticketKey(ticket.playerId()));
            args.add(ticket.ticketId());
        }
        return (int) evalInteger("删一组票", TicketScripts.DEL_GROUP, keys, d, args.toArray());
    }

    @Override
    public int requeueFront(QueueRef queue, String requeueToken, List<TicketRef> survivorsInOrder, long queuedTtlMs, long notBeforeDelayMs,
                            Deadline d) {
        Objects.requireNonNull(queue, "queue");
        requireTtl(queuedTtlMs);
        if (requeueToken == null || requeueToken.isEmpty()) {
            throw new IllegalArgumentException("回队首必须带 token");
        }
        if (notBeforeDelayMs < 0) {
            throw new IllegalArgumentException("退避时长不能为负: " + notBeforeDelayMs);
        }
        requireDistinct(survivorsInOrder.stream().map(TicketRef::playerId).toList(), "回队首");
        if (survivorsInOrder.isEmpty()) {
            return 0;
        }
        List<Object> keys = new ArrayList<>(4 + survivorsInOrder.size());
        keys.add(RedisKeys.matchQueueIndex());
        keys.add(queue.queueKey());
        keys.add(queue.rankKey());
        keys.add(RedisKeys.matchRequeueMarker(requeueToken));
        List<Object> args = new ArrayList<>(4 + 2 * survivorsInOrder.size());
        args.add(Long.toString(queuedTtlMs));
        args.add(Long.toString(notBeforeDelayMs));
        args.add(Long.toString(RatingReader.DEFAULT_CENTI));
        args.add(Long.toString(REQUEUE_MARKER_TTL_MS));
        for (TicketRef survivor : survivorsInOrder) {
            keys.add(ticketKey(survivor.playerId()));
            args.add(TicketCodec.member(survivor.playerId()));
            args.add(survivor.ticketId());
        }
        return (int) evalInteger("回队首", TicketScripts.REQUEUE, keys, d, args.toArray());
    }

    // ================================================================ 注册集、深度、凑单锁

    @Override
    public Set<String> queueIndex(Deadline d) {
        Set<String> members = await("读队列注册集", d, () -> redis.<String>getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).readAllAsync());
        return new LinkedHashSet<>(members);
    }

    @Override
    public long queueLength(QueueRef queue, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        Integer size = await("读队列长度", d, () -> redis.<String>getList(queue.queueKey(), StringCodec.INSTANCE).sizeAsync());
        return size;
    }

    @Override
    public boolean pruneIfEmpty(QueueRef queue, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        return evalInteger("剔除空队列", TicketScripts.PRUNE, List.of(RedisKeys.matchQueueIndex(), queue.queueKey(), queue.rankKey()), d) == 1;
    }

    @Override
    public boolean tryLockQueue(QueueRef queue, String instanceId, long ttlMs, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        requireTtl(ttlMs);
        requireInstance(instanceId);
        Boolean acquired = await("抢凑单锁", d,
                () -> redis.<String>getBucket(queue.lockKey(), StringCodec.INSTANCE).setIfAbsentAsync(instanceId, Duration.ofMillis(ttlMs)));
        return acquired;
    }

    @Override
    public void unlockQueue(QueueRef queue, String instanceId, Deadline d) {
        Objects.requireNonNull(queue, "queue");
        requireInstance(instanceId);
        evalInteger("释放凑单锁", TicketScripts.LOCK_RELEASE, List.of(queue.lockKey()), d, instanceId);
    }

    // ================================================================ 内部

    private static String ticketKey(long playerId) {
        requirePlayer(playerId);
        return RedisKeys.matchTicket(playerId);
    }

    private long evalInteger(String what, String lua, List<Object> keys, Deadline d, Object... args) {
        Long reply = await(what, d, () -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, lua,
                RScript.ReturnType.INTEGER, keys, args));
        return reply;
    }

    private List<Object> evalList(String what, String lua, List<Object> keys, Deadline d, Object... args) {
        return await(what, d, () -> redis.getScript(StringCodec.INSTANCE).<List<Object>>evalAsync(RScript.Mode.READ_WRITE, lua,
                RScript.ReturnType.MULTI, keys, args));
    }

    /**
     * 发出一次异步调用并在截止内等结果。截止已过不发；同步抛出的异常（客户端已关闭等）、异常完成、等超时、空回复，一律 {@link Deadline.DependencyException}。
     */
    private static <T> T await(String what, Deadline d, Supplier<? extends CompletionStage<T>> call) {
        Objects.requireNonNull(d, "deadline");
        if (d.expired()) {
            throw new Deadline.DependencyException(what + " 之前请求预算已用完（没有发出）");
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
        T reply = d.await(stage, what);
        if (reply == null) {
            throw new Deadline.DependencyException(what + " 得到空回复");
        }
        return reply;
    }

    private static long integerAt(List<Object> reply, int index, String what) {
        if (index >= reply.size() || !(reply.get(index) instanceof Long value)) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 第 " + index + " 项不是整数 " + reply);
        }
        return value;
    }

    private static String textAt(List<Object> reply, int index, String what) {
        if (index >= reply.size() || reply.get(index) == null) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 缺第 " + index + " 项 " + reply);
        }
        return reply.get(index).toString();
    }

    private static List<Object> slice(List<Object> reply, int from, int count, String what) {
        if (count < 0 || from + count > reply.size()) {
            throw new Deadline.DependencyException(what + " 的回复形状不对: 长度 " + reply.size() + " 不够取 [" + from + ", " + (from + count) + ")");
        }
        return reply.subList(from, from + count);
    }

    private static void requireTtl(long ttlMs) {
        if (ttlMs < 1) {
            throw new IllegalArgumentException("TTL 必须 ≥ 1 ms: " + ttlMs);
        }
    }

    private static void requirePlayer(long playerId) {
        if (playerId == 0) {
            throw new IllegalArgumentException("玩家号不能为 0");
        }
    }

    private static void requireInstance(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("实例标识不能为空");
        }
    }

    private static void requireDistinct(List<Long> playerIds, String what) {
        Set<Long> seen = new HashSet<>();
        for (Long playerId : playerIds) {
            if (!seen.add(playerId)) {
                throw new IllegalArgumentException(what + "的玩家号重复: " + Long.toUnsignedString(playerId));
            }
        }
    }
}
