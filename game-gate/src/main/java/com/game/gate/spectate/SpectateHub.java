package com.game.gate.spectate;

import com.game.contract.MessageIdRegistry;
import com.game.discovery.spectate.SpectateFrameChannel;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.SpectateFrameResult;
import com.game.gate.session.ClientSession;
import com.game.gate.session.SpectateSubscriptions;
import com.game.net.client.ClientFrames;
import com.game.proto.MessageContent;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.SpectateTicketPayload;
import com.game.proto.TurnResultS2C;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import com.game.proto.contracts.kafka.eSpectateFrameKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.eSpectateEndReason;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gate 的观战订阅中心（mmorpg battle-spectate-tiered-fanout.md §15.5 的 Java 版；中继档与广播档合成一种机制，§15.7）：本机有观众的场次才分片订阅
 * 这一场的频道，帧到了编码一次、扇出给本机订阅会话。客户端契约与 C++ 版一致：首帧 161，之后每回合 158，终局 166，一个会话同时只订一场。
 *
 * <p><b>线程</b>：全部状态只在 {@code executor}（生产是单线程的 {@code gate-spectate}）上读写，对外方法与 Redisson 回调都只投递任务，所以这里不阻塞、无锁，
 * 也不让 Redis 回调线程做扇出。写连接只用 {@link ClientSession#writeEncodedBelow}（Netty 线程安全的 API，不碰会话状态）。
 * 会话对象从订阅到 {@link #drop} 期间被持有；{@code ClientDispatcher} 在断线与玩家解绑时一定调 {@code drop}，关闭之后不再留引用（AGENTS.md §3）。
 *
 * <p><b>收帧</b>：种类与消息号对不上、实例不符都丢弃；两条水位（§15.5）——TURN 只收比已收 TURN 更大的回合，STATE 只收不比缓存旧的快照、只更新缓存；
 * END 只收一次、扇出后留 {@link #TOMBSTONE} 墓碑，墓碑期内的新订阅直接回 166。pub/sub 至多一次，每帧都是全量状态，丢帧自愈。
 *
 * <p><b>订阅顺序</b>：先建频道订阅、后读最新帧键，二者之间发布的帧不会漏（battle 端写完键才发布，同一场的帧按场串行）；读到的旧帧被回合号挡掉。
 * 频道订阅失败（典型是进程的订阅容量到顶）不重试，当场给观众发 {@code SOURCE_LOST}，不让他们空等 {@link #IDLE_TIMEOUT}；客户端重新进观战时再订。
 *
 * <p><b>扇出</b>：帧只编码一次（{@link ClientFrames#encode}），每条连接写一份 {@code retainedDuplicate()}，写完或写失败由 Netty 释放。
 * 写缓冲过了 {@link #SOFT_WATERMARK_BYTES} 就跳过这一帧并计数，不断开大厅连接；终局帧不看软水位。
 *
 * <p><b>收尾</b>：{@link #sweep} 定期巡检：有订阅者的条目 {@link #IDLE_TIMEOUT} 没有新帧、或过了战斗期限 + {@link #DEADLINE_GRACE}，先读一次最新帧键：
 * 读到同实例的 END 就按真实终局收尾（订阅连接断开时 END 恰好发出，pub/sub 不补发，键里还有）；读不到才合成 166（{@code SOURCE_LOST}）并删除。
 * 丢的是最后一回合 TURN 而 END 到了，这一半补不回来：键已被 END 覆盖，这是至多一次通道的已知代价（观众少看一回合，结局不错）。
 * 没有订阅者的条目 linger {@link #LINGER} 后退订（短暂的换场 / 重连不必来回订阅）；墓碑到期删除。
 */
public final class SpectateHub implements SpectateSubscriptions, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectateHub.class);

    /** 没有新帧的上限：必须大于手动回合窗口 6 s 加结果提交等待（§5.3）。 */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEADLINE_GRACE = Duration.ofSeconds(60);
    static final Duration LINGER = Duration.ofSeconds(30);
    static final Duration TOMBSTONE = Duration.ofMinutes(10);
    /** 写缓冲软水位（§15.5）：远低于 2 MB 的断连高水位，慢客户端只丢帧。 */
    static final long SOFT_WATERMARK_BYTES = 256 * 1024;
    /** 终局帧不看软水位：跳过就再也没有下一帧，客户端会一直等；真到了 2 MB 断连高水位，连接本身也就没了。 */
    private static final long MUST_DELIVER = Long.MAX_VALUE;

    /** hub 需要的观战频道操作（生产 {@link #of(SpectateFrameChannel)}）。 */
    public interface FrameSource {

        /** @param frames 在任意线程回调 */
        CompletionStage<SpectateFrameChannel.Subscription> subscribe(long battleId, Consumer<byte[]> frames);

        CompletionStage<Optional<byte[]>> readState(long battleId);
    }

    public static FrameSource of(SpectateFrameChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return new FrameSource() {
            @Override
            public CompletionStage<SpectateFrameChannel.Subscription> subscribe(long battleId, Consumer<byte[]> frames) {
                return channel.subscribe(battleId, frames);
            }

            @Override
            public CompletionStage<Optional<byte[]>> readState(long battleId) {
                return channel.readState(battleId);
            }
        };
    }

    /** 三种观战帧的消息号（按契约解析，缺一个即启动失败）：核对帧的种类、自己组 161 与 166。 */
    public record MessageIds(int spectateState, int spectateTurn, int spectateEnd) {

        private static final String SERVICE = "BattleClientPlayer";

        public static MessageIds resolve(MessageIdRegistry registry) {
            return new MessageIds(registry.requireId(SERVICE, "NotifySpectateState"), registry.requireId(SERVICE, "NotifySpectateTurnResult"),
                    registry.requireId(SERVICE, "NotifySpectateEnd"));
        }
    }

    /** 一场战斗在本机的状态。 */
    private static final class Entry {
        final long battleId;
        String instanceId;
        long deadlineMs;
        final Set<ClientSession> subscribers = new LinkedHashSet<>();
        /** 已订阅、还没发过首帧 161 的会话（没有缓存时先记在这里，下一帧到了先给它们 161）。 */
        final Set<ClientSession> awaitingFirst = new LinkedHashSet<>();
        /** 最新的状态帧（161 或 158 的原帧）。 */
        MessageContent latest;
        boolean latestIsTurn;
        /** {@link #latest} 对应的 161：158 要解析出 state 才能合成，只在有新订阅者时合成一次。 */
        MessageContent firstFrame;
        /** 已收的最大 TURN 回合（uint32 按无符号放宽到 long；-1 = 还没有），只由 TURN 推进。 */
        long lastTurnRound = -1;
        /** {@link #latest} 所处的回合：STATE(r) 记 r，TURN(r) 记 r + 1。STATE(r) 是第 r 回合结算前的快照，紧跟的 TURN(r) 不是重复，所以不能拿它推进上一条水位。 */
        long cachedStateRound = -1;
        long lastFrameNanos;
        long emptySinceNanos;
        /** 终局帧；非 null 即已结束（墓碑），不再收帧。 */
        MessageContent end;
        long endedAtNanos;
        SpectateFrameChannel.Subscription channel;
        /** 判定源站失联之前的那次读键还在途：巡检不重复发，也不抢先收尾。 */
        boolean finalReadInFlight;

        Entry(long battleId) {
            this.battleId = battleId;
        }

        /** 实例换了（凭证为准）：旧实例的缓存与终局都作废；留在条目里的订阅者继续看新实例，从下一帧的 161 重新开始。 */
        void reset(String newInstance) {
            instanceId = newInstance;
            deadlineMs = 0;
            latest = null;
            latestIsTurn = false;
            firstFrame = null;
            lastTurnRound = -1;
            cachedStateRound = -1;
            end = null;
            awaitingFirst.addAll(subscribers);
        }
    }

    private final Executor executor;
    private final FrameSource source;
    private final MessageIds ids;
    private final GateMetrics metrics;
    private final LongSupplier nanoClock;
    private final LongSupplier wallClockMs;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<ClientSession, Entry> bySession = new HashMap<>();
    /** 指标线程读的快照（每个任务结束时刷新）。 */
    private volatile int subscriberCount;
    private volatile int entryCount;

    /**
     * @param executor    hub 的唯一执行线程（测试可传同步执行器）
     * @param nanoClock   单调时钟（空闲与墓碑）
     * @param wallClockMs 墙钟（与 Unix 毫秒的战斗期限比较）
     */
    public SpectateHub(Executor executor, FrameSource source, MessageIds ids, GateMetrics metrics, LongSupplier nanoClock, LongSupplier wallClockMs) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.source = Objects.requireNonNull(source, "source");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.wallClockMs = Objects.requireNonNull(wallClockMs, "wallClockMs");
        metrics.bindSpectate(() -> subscriberCount, () -> entryCount);
    }

    // ================================================================ 对外（任意线程）

    @Override
    public void subscribe(ClientSession session, SpectateTicketPayload ticket) {
        post(() -> doSubscribe(session, ticket));
    }

    @Override
    public void unsubscribe(ClientSession session, long battleId) {
        post(() -> {
            Entry entry = bySession.get(session);
            if (entry != null && entry.battleId == battleId) {
                removeSubscriber(entry, session);
            }
        });
    }

    @Override
    public void drop(ClientSession session) {
        post(() -> {
            Entry entry = bySession.get(session);
            if (entry != null) {
                removeSubscriber(entry, session);
            }
        });
    }

    /** 巡检一次（定时任务调）：投递到 hub 线程上执行，不抛异常。 */
    public void sweepLater() {
        post(this::sweep);
    }

    /** 进程退出：退订全部频道、清空。之后到达的帧没有条目，被丢弃。 */
    @Override
    public void close() {
        post(() -> {
            for (Entry entry : List.copyOf(entries.values())) {
                retire(entry);
            }
            bySession.clear();
        });
    }

    private void post(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("观战 hub 任务出错（状态保持上一步的结果）", e);
                }
                subscriberCount = bySession.size();
                entryCount = entries.size();
            });
        } catch (RejectedExecutionException e) {
            log.debug("观战 hub 线程已关闭，丢弃任务");
        }
    }

    // ================================================================ 订阅（hub 线程）

    private void doSubscribe(ClientSession session, SpectateTicketPayload ticket) {
        long battleId = ticket.getBattleId();
        String instance = ticket.getBattleInstanceId();
        Entry previous = bySession.get(session);
        if (previous != null && previous.battleId != battleId) {
            removeSubscriber(previous, session);
        }
        Entry entry = entries.get(battleId);
        boolean needChannel = false;
        if (entry != null && !instance.equals(entry.instanceId)) {
            // 凭证是 match 按落点签的权威：实例不同就以凭证为准（旧条目的缓存与墓碑属于旧实例）
            needChannel = entry.end != null; // 墓碑已经退了频道
            entry.reset(instance);
            entry.lastFrameNanos = nanoClock.getAsLong();
        }
        if (entry == null) {
            entry = new Entry(battleId);
            entry.instanceId = instance;
            entry.lastFrameNanos = nanoClock.getAsLong();
            entries.put(battleId, entry);
            needChannel = true;
        }
        if (ticket.getDeadlineMs() != 0) {
            entry.deadlineMs = ticket.getDeadlineMs();
        }
        if (entry.end != null) {
            // 墓碑：这场已经结束，直接给 166，不进订阅表
            writeOne(session, entry.end, MUST_DELIVER);
            return;
        }
        entry.subscribers.add(session);
        bySession.put(session, entry);
        deliverFirst(entry, session);
        if (needChannel) {
            attachChannel(entry); // 排在进订阅表之后：订阅失败要给这名观众收尾
        }
    }

    private void attachChannel(Entry entry) {
        long battleId = entry.battleId;
        source.subscribe(battleId, bytes -> post(() -> onChannelFrame(battleId, bytes)))
                .whenComplete((subscription, error) -> post(() -> onChannelAttached(entry, subscription, error)));
    }

    private void onChannelAttached(Entry entry, SpectateFrameChannel.Subscription subscription, Throwable error) {
        boolean current = entries.get(entry.battleId) == entry && entry.end == null;
        if (error != null) {
            metrics.spectateChannelFailed();
            log.warn("订阅观战频道失败 battle_id={}: {}", Long.toUnsignedString(entry.battleId), error.toString());
            // 没有频道就没有帧：不重试、不空等，当场收尾；客户端重新进观战时再订。实例换了之后的第二次 attach 已经成功的，这次失败无所谓
            if (current && entry.channel == null) {
                if (entry.subscribers.isEmpty()) {
                    retire(entry);
                } else {
                    sourceLost(entry);
                }
            }
            return;
        }
        // 建好之前条目已经没了或已结束；或者 END 之后实例换了，两次 attach 都在途，晚到的这个多余：一个条目只留一个订阅，否则先到的句柄被覆盖后再也退不掉
        if (!current || entry.channel != null) {
            subscription.close();
            return;
        }
        entry.channel = subscription;
        source.readState(entry.battleId).whenComplete((state, readError) -> post(() -> onStateRead(entry, state, readError)));
    }

    private void onStateRead(Entry entry, Optional<byte[]> state, Throwable error) {
        if (error != null) {
            log.warn("读观战最新帧失败（等下一帧） battle_id={}: {}", Long.toUnsignedString(entry.battleId), error.toString());
            return;
        }
        if (entries.get(entry.battleId) == entry && state.isPresent()) {
            accept(entry, state.get(), false);
        }
    }

    private void removeSubscriber(Entry entry, ClientSession session) {
        entry.subscribers.remove(session);
        entry.awaitingFirst.remove(session);
        bySession.remove(session);
        if (entry.subscribers.isEmpty()) {
            entry.emptySinceNanos = nanoClock.getAsLong();
        }
    }

    // ================================================================ 收帧（hub 线程）

    private void onChannelFrame(long battleId, byte[] bytes) {
        Entry entry = entries.get(battleId);
        if (entry != null) {
            accept(entry, bytes, true);
        }
    }

    /** @param live true = 频道上刚发布的帧（证明源站活着）；false = 最新帧键里读到的，不刷新空闲计时 */
    private void accept(Entry entry, byte[] bytes, boolean live) {
        if (entry.end != null) {
            return; // END 只收一次
        }
        SpectateFrameEvent event;
        MessageContent content;
        try {
            event = SpectateFrameEvent.parseFrom(bytes);
            content = MessageContent.parseFrom(event.getClientFrame());
        } catch (InvalidProtocolBufferException e) {
            metrics.spectateFrame(SpectateFrameResult.INVALID);
            return;
        }
        if (!kindMatchesMessage(event.getKind(), content.getMessageId())) {
            metrics.spectateFrame(SpectateFrameResult.INVALID);
            return;
        }
        if (!entry.instanceId.equals(event.getBattleInstanceId())) {
            metrics.spectateFrame(SpectateFrameResult.STALE_INSTANCE);
            return;
        }
        if (live) {
            entry.lastFrameNanos = nanoClock.getAsLong();
        }
        if (event.getDeadlineMs() != 0) {
            entry.deadlineMs = event.getDeadlineMs();
        }
        long round = Integer.toUnsignedLong(event.getRoundIndex());
        switch (event.getKind()) {
            case SPECTATE_FRAME_TURN -> {
                if (round <= entry.lastTurnRound) {
                    metrics.spectateFrame(SpectateFrameResult.DUPLICATE);
                    return;
                }
                entry.lastTurnRound = round;
                // 晚于更新的 STATE 到达的 TURN：照常扇出（观众还没看过这回合），但不把缓存往回换
                if (round + 1 >= entry.cachedStateRound) {
                    cache(entry, content, true, round + 1);
                }
                // 还没拿到首帧的先收 161（状态取自缓存），其余的收 158
                List<ClientSession> watching = new ArrayList<>(entry.subscribers);
                watching.removeAll(entry.awaitingFirst);
                flushAwaitingFirst(entry);
                fanOut(watching, content, SOFT_WATERMARK_BYTES);
            }
            case SPECTATE_FRAME_STATE -> {
                // 比缓存旧的快照不要：包括第 r 回合结算后才到的 STATE(r)（缓存已是 r + 1）
                if (round < entry.cachedStateRound) {
                    metrics.spectateFrame(SpectateFrameResult.DUPLICATE);
                    return;
                }
                cache(entry, content, false, round);
                flushAwaitingFirst(entry);
            }
            case SPECTATE_FRAME_END -> {
                metrics.spectateFrame(SpectateFrameResult.ACCEPTED);
                finish(entry, content);
                return;
            }
            default -> throw new IllegalStateException("kindMatchesMessage 已拒绝其它种类：" + event.getKind());
        }
        metrics.spectateFrame(SpectateFrameResult.ACCEPTED);
    }

    /** 种类与客户端消息号必须对得上：首帧合成按种类区分 161 / 158，错配的帧发下去客户端状态机会错乱。 */
    private boolean kindMatchesMessage(eSpectateFrameKind kind, int messageId) {
        return switch (kind) {
            case SPECTATE_FRAME_STATE -> messageId == ids.spectateState();
            case SPECTATE_FRAME_TURN -> messageId == ids.spectateTurn();
            case SPECTATE_FRAME_END -> messageId == ids.spectateEnd();
            default -> false;
        };
    }

    private static void cache(Entry entry, MessageContent content, boolean turn, long stateRound) {
        entry.latest = content;
        entry.latestIsTurn = turn;
        entry.firstFrame = null;
        entry.cachedStateRound = stateRound;
    }

    /** 终局：扇出、清订阅、退频道，留墓碑给迟到的订阅者。 */
    private void finish(Entry entry, MessageContent end) {
        fanOut(entry.subscribers, end, MUST_DELIVER);
        for (ClientSession session : entry.subscribers) {
            bySession.remove(session);
        }
        entry.subscribers.clear();
        entry.awaitingFirst.clear();
        // 墓碑要留 10 分钟，只留 166：状态帧没人再用了
        entry.latest = null;
        entry.firstFrame = null;
        entry.end = end;
        entry.endedAtNanos = nanoClock.getAsLong();
        closeChannel(entry);
    }

    // ================================================================ 下发（hub 线程）

    private void deliverFirst(Entry entry, ClientSession session) {
        MessageContent first = firstFrameOf(entry);
        if (first == null || !writeOne(session, first, SOFT_WATERMARK_BYTES)) {
            entry.awaitingFirst.add(session); // 没有缓存，或这一帧被软水位跳过：下一帧再给 161
        } else {
            entry.awaitingFirst.remove(session);
        }
    }

    private void flushAwaitingFirst(Entry entry) {
        if (entry.awaitingFirst.isEmpty()) {
            return;
        }
        for (ClientSession session : List.copyOf(entry.awaitingFirst)) {
            deliverFirst(entry, session);
        }
    }

    /** 缓存本身是 161 原样用；是 158 就取其 state 合成（{@code observer_count = 0}：大厅档人数未知，§15.1），每帧至多合成一次。 */
    private MessageContent firstFrameOf(Entry entry) {
        if (entry.latest == null) {
            return null;
        }
        if (!entry.latestIsTurn) {
            return entry.latest;
        }
        if (entry.firstFrame == null) {
            TurnResultS2C turn;
            try {
                turn = TurnResultS2C.parseFrom(entry.latest.getSerializedMessage());
            } catch (InvalidProtocolBufferException e) {
                log.warn("观战帧 158 解析不了，合成不出首帧（等下一帧） battle_id={}", Long.toUnsignedString(entry.battleId));
                return null;
            }
            if (!turn.hasState()) {
                log.warn("观战帧 158 没带状态，合成不出首帧（等下一帧） battle_id={}", Long.toUnsignedString(entry.battleId));
                return null;
            }
            entry.firstFrame = MessageContent.newBuilder().setMessageId(ids.spectateState())
                    .setSerializedMessage(SpectateStateS2C.newBuilder().setState(turn.getState()).build().toByteString())
                    .build();
        }
        return entry.firstFrame;
    }

    /** @return false = 跳过了（写缓冲到了 {@code softLimit}，或连接不可写） */
    private boolean writeOne(ClientSession session, MessageContent content, long softLimit) {
        ByteBuf encoded = ClientFrames.encode(ByteBufAllocator.DEFAULT, content);
        boolean written = session.writeEncodedBelow(encoded, softLimit);
        metrics.spectateFanout(written ? 1 : 0, written ? 0 : 1);
        return written;
    }

    /** 帧只编码一次；各连接一份引用，原件在这里释放。 */
    private void fanOut(Collection<ClientSession> sessions, MessageContent content, long softLimit) {
        if (sessions.isEmpty()) {
            return;
        }
        int delivered = 0;
        int skipped = 0;
        ByteBuf encoded = ClientFrames.encode(ByteBufAllocator.DEFAULT, content);
        try {
            for (ClientSession session : sessions) {
                if (session.writeEncodedBelow(encoded.retainedDuplicate(), softLimit)) {
                    delivered++;
                } else {
                    skipped++;
                }
            }
        } finally {
            encoded.release();
        }
        metrics.spectateFanout(delivered, skipped);
    }

    // ================================================================ 巡检（hub 线程）

    /** 巡检一次。测试直接调（同步执行器）。 */
    void sweep() {
        long now = nanoClock.getAsLong();
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.end != null) {
                if (now - entry.endedAtNanos >= TOMBSTONE.toNanos()) {
                    retire(entry);
                }
            } else if (entry.subscribers.isEmpty()) {
                if (now - entry.emptySinceNanos >= LINGER.toNanos()) {
                    retire(entry);
                }
            } else if (!entry.finalReadInFlight && sourceSilent(entry)) {
                readBeforeSourceLost(entry);
            }
        }
    }

    /** 判定源站失联的条件：空闲超时，或过了战斗期限 + 余量（过了期限还有帧也算：房间早该收尾了）。 */
    private boolean sourceSilent(Entry entry) {
        return nanoClock.getAsLong() - entry.lastFrameNanos >= IDLE_TIMEOUT.toNanos()
                || (entry.deadlineMs != 0 && Long.compareUnsigned(wallClockMs.getAsLong(), entry.deadlineMs + DEADLINE_GRACE.toMillis()) > 0);
    }

    /** 收尾之前先看一眼最新帧键：pub/sub 丢了 END 而键里还在时，观众应该收到真实终局，不是 {@code SOURCE_LOST}。 */
    private void readBeforeSourceLost(Entry entry) {
        entry.finalReadInFlight = true;
        CompletionStage<Optional<byte[]>> read;
        try {
            read = source.readState(entry.battleId);
        } catch (RuntimeException e) {
            onFinalRead(entry, Optional.empty(), e); // 客户端已关闭（进程退出中）：同读不到
            return;
        }
        read.whenComplete((state, error) -> post(() -> onFinalRead(entry, state, error)));
    }

    private void onFinalRead(Entry entry, Optional<byte[]> state, Throwable error) {
        entry.finalReadInFlight = false;
        // 读键期间这场已收尾、观众都走了，或者又有新帧到了（源站还活着）：什么都不做，下一轮巡检重新判
        if (entries.get(entry.battleId) != entry || entry.end != null || entry.subscribers.isEmpty() || !sourceSilent(entry)) {
            return;
        }
        if (error != null) {
            log.warn("读观战最新帧失败（按源站失联收尾） battle_id={}: {}", Long.toUnsignedString(entry.battleId), error.toString());
        } else if (state.isPresent()) {
            accept(entry, state.get(), false);
            if (entry.end != null) {
                return; // 键里是同实例的 END：accept 已按真实终局扇出并留墓碑
            }
        }
        sourceLost(entry);
    }

    private void sourceLost(Entry entry) {
        metrics.spectateSourceLost();
        log.info("观战源站失联，gate 本地收尾 battle_id={} subscribers={}", Long.toUnsignedString(entry.battleId), entry.subscribers.size());
        fanOut(entry.subscribers, MessageContent.newBuilder().setMessageId(ids.spectateEnd())
                .setSerializedMessage(SpectateEndS2C.newBuilder().setBattleId(entry.battleId).setOutcome(eBattleOutcome.BATTLE_OUTCOME_ONGOING)
                        .setReason(eSpectateEndReason.SPECTATE_END_SOURCE_LOST).build().toByteString())
                .build(), MUST_DELIVER);
        for (ClientSession session : entry.subscribers) {
            bySession.remove(session);
        }
        entry.subscribers.clear();
        entry.awaitingFirst.clear();
        retire(entry);
    }

    private void retire(Entry entry) {
        entries.remove(entry.battleId, entry);
        closeChannel(entry);
    }

    private static void closeChannel(Entry entry) {
        SpectateFrameChannel.Subscription channel = entry.channel;
        entry.channel = null;
        if (channel != null) {
            channel.close();
        }
    }
}
