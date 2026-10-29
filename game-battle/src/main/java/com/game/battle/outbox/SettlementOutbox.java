package com.game.battle.outbox;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.battle.outbox.OutboxMetrics.SettlementEvent;
import com.game.battle.port.SettlementSink;
import com.game.battle.room.BattleScheduler;
import com.game.battle.room.Cancellable;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleSettlementEvent;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 兼容通道的结算发件箱（scene-battle-spec §3.3–§3.5、§7.15）：先调用落库，再定位并首投。
 * 写入成功或结果未知都登记原 ID 和不可变原字节；UNKNOWN 不等于回滚，仍保留既有一次尽力首投。
 * 已有墓碑使 STORE 返回 -1 时不登记、不投递；不新增墓碑，也不从缺失记录推断未知写入已经销账。
 *
 * <p>每 10 秒探测，最多 12 次重投判定，登记最多保留 {@link BattleRedis#OUTBOX_MAX_AGE}。未知写入每轮须读取并精确核对
 * 原字节后才能定位重投；不存在、异值或读失败都保留 UNKNOWN，不计重投次数、不补写、不调用取代销账。
 * 正常确认写入的既有重投和销账规则保留。投递应答只计数，不能作为资产已经落盘的证明。
 * 同进程在途或登记期间拒绝同 ID 不同字节，旧 store / probe 回包不能替换新登记；这不是跨写者 Redis 围栏。
 *
 * <p>全部名单操作归属 {@code battle-outbox} 线程，异步端口回包投递回该线程。{@link #drainAndClose} 有界等待落库与首投，
 * 然后丢弃内存登记。年龄或次数耗尽只停止内存重投，不能证明已销账或原件仍存在。兼容通道仍有既有 TTL 与重启恢复限制，
 * 不能代替 SQL v1 的永久原件和持久回执。已有墓碑的保护也只在其 TTL 内有效。
 */
public final class SettlementOutbox implements SettlementSink {

    private static final Logger log = LoggerFactory.getLogger(SettlementOutbox.class);

    /** 待结算记录的 Redis 操作（生产 = {@link BattleRedis}）。future 在任意线程上完成，出错以异常完成。 */
    public interface Store {

        /**
         * {@code STORE_SETTLEMENT}：回落库后的字段数（≥ 1）；这一局已经销账（scene 的 ACK / 本端的「已取代」留下的墓碑还在）→ <b>没有写入</b>、
         * 回 {@link BattleRedis#STORE_ALREADY_SETTLED}（-1）。
         */
        CompletableFuture<Long> store(long playerId, long battleId, byte[] payload);

        /** HEXISTS：记录还在吗。问不到结论（出错、空回复）以异常完成，不回 false。 */
        CompletableFuture<Boolean> exists(long playerId, long battleId);

        /** 读取持久原件供未知写入核对。空值只代表当前没有原件，不是销账证明。 */
        default CompletableFuture<byte[]> readOriginal(long playerId, long battleId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("未装配结算原件读取"));
        }

        /** {@code ACK_IF_SUPERSEDED}：3 已销账 / 0 锁不在 / 1 仍是本局 / 2 已被取代（删了记录并留下已销账墓碑）。 */
        CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId);

        static Store redis(BattleRedis redis) {
            Objects.requireNonNull(redis, "redis");
            return new Store() {
                @Override
                public CompletableFuture<Long> store(long playerId, long battleId, byte[] payload) {
                    return redis.storeSettlement(playerId, battleId, payload, BattleRedis.SETTLEMENT_TTL_SEC);
                }

                @Override
                public CompletableFuture<Boolean> exists(long playerId, long battleId) {
                    return redis.settlementExists(playerId, battleId);
                }

                @Override
                public CompletableFuture<byte[]> readOriginal(long playerId, long battleId) {
                    return redis.readSettlement(playerId, battleId);
                }

                @Override
                public CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId) {
                    return redis.ackIfSuperseded(playerId, battleId);
                }
            };
        }
    }

    /** 玩家此刻的持有者节点（生产 = {@code SceneAssetLocator.resolveAsync}；future 不异常完成，异常完成也按故障处理）。 */
    @FunctionalInterface
    public interface Locator {
        CompletableFuture<Resolution> locate(long playerId);
    }

    /** 投递到 scene（生产 = {@code NodeRpcClients<SceneBattleService>}）；future 异常完成 = 传输失败。 */
    @FunctionalInterface
    public interface Transport {
        CompletableFuture<SceneBattleReply> applySettlement(SceneAssetEndpoint endpoint, SceneBattleCall call);
    }

    private record Key(long battleId, long playerId) {
    }

    /** 一条登记（只在发件箱线程上读写）。 */
    private static final class Entry {
        final long playerId;
        final long battleId;
        final ByteString payload;
        /** 开局时的 scene 节点（只进日志）。 */
        final int originalSceneNode;
        final long enqueuedAtMs;
        final boolean persistenceUnconfirmed;
        int attempts;
        boolean probing;

        Entry(long playerId, long battleId, ByteString payload, int originalSceneNode, long enqueuedAtMs,
              boolean persistenceUnconfirmed) {
            this.playerId = playerId;
            this.battleId = battleId;
            this.payload = payload;
            this.originalSceneNode = originalSceneNode;
            this.enqueuedAtMs = enqueuedAtMs;
            this.persistenceUnconfirmed = persistenceUnconfirmed;
        }
    }

    /** 同一键全部未完成写调用共享字节约束；最新回包的登记权与约束的寿命分别维护。 */
    private static final class PendingStores {
        final ByteString payload;
        Object latest;
        int unfinished;

        PendingStores(ByteString payload) {
            this.payload = payload;
        }

        Object begin() {
            latest = new Object();
            unfinished++;
            return latest;
        }

        boolean finish(Object attempt) {
            unfinished--;
            return latest == attempt;
        }
    }

    private final BattleScheduler outbox;
    private final Store store;
    private final Locator locator;
    private final Transport transport;
    private final OutboxMetrics metrics;
    private final LongSupplier clockMillis;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    /** 全部未完成调用归零前保留共同 payload；不能随最新回包或登记摘除而丢掉旧写约束。 */
    private final Map<Key, PendingStores> pendingStores = new LinkedHashMap<>();
    /** 在途的落库 + 首投（停机排空等它归零；任意线程读写）。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    private Cancellable timer;
    private volatile boolean closed;

    /**
     * @param outbox      发件箱线程（{@code battle-outbox}；测试用手动调度器）
     * @param clockMillis 单调毫秒（登记年龄）
     */
    public SettlementOutbox(BattleScheduler outbox, Store store, Locator locator, Transport transport, OutboxMetrics metrics,
                            LongSupplier clockMillis) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.store = Objects.requireNonNull(store, "store");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** {@link SettlementSink}：在 {@code battle-logic} 上调用，只把任务交给发件箱线程（不阻塞、不抛异常）。 */
    @Override
    public void dispatch(BattleRouting routing, long playerId, BattleSettlementData settlement) {
        if (settlement == null) {
            // 调用方缺陷。在这里挡住：否则空指针会抛在发件箱线程上，在途计数永不归还，停机排空要白等满时限
            log.error("结算发件箱收到空的结算（调用方缺陷），不落库也不投递 player={}", Long.toUnsignedString(playerId));
            return;
        }
        if (closed) {
            log.error("结算发件箱已关闭，这份结算没有落库也没有投递（留给 scene 的 rescue / 进场恢复兜底不了：记录不在 Redis） battle_id={} player={}",
                    Long.toUnsignedString(settlement.getBattleId()), Long.toUnsignedString(playerId));
            return;
        }
        inFlight.incrementAndGet();
        try {
            outbox.execute(() -> onDispatch(routing, playerId, settlement));
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            log.error("结算发件箱线程已停，这份结算没有落库也没有投递 battle_id={} player={}", Long.toUnsignedString(settlement.getBattleId()),
                    Long.toUnsignedString(playerId));
        }
    }

    /** 发件箱线程上：序列化 → 落库，结局回来再投递。 */
    private void onDispatch(BattleRouting routing, long playerId, BattleSettlementData settlement) {
        long battleId = settlement.getBattleId();
        byte[] payload;
        try {
            payload = BattleSettlementEvent.newBuilder().setSettlement(settlement).build().toByteArray();
        } catch (RuntimeException e) {
            metrics.settlement(SettlementEvent.SERIALIZE_FAILED);
            log.error("结算序列化失败，什么都不发 battle_id={} player={}", Long.toUnsignedString(battleId), Long.toUnsignedString(playerId), e);
            inFlight.decrementAndGet();
            return;
        }
        ByteString bytes = ByteString.copyFrom(payload);
        Key key = new Key(battleId, playerId);
        Entry registered = entries.get(key);
        PendingStores pending = pendingStores.get(key);
        if ((registered != null && !registered.payload.equals(bytes))
                || (pending != null && !pending.payload.equals(bytes))) {
            metrics.settlement(SettlementEvent.PAYLOAD_CONFLICT);
            log.error("同一结算原ID收到不同字节，拒绝覆盖或投递 battle_id={} player={}",
                    Long.toUnsignedString(battleId), Long.toUnsignedString(playerId));
            finishFirst(true);
            return;
        }
        PendingStores calls = pendingStores.computeIfAbsent(key, ignored -> new PendingStores(bytes));
        Object attempt = calls.begin();
        CompletableFuture<Long> stored;
        try {
            stored = store.store(playerId, battleId, payload);
        } catch (RuntimeException e) {
            stored = CompletableFuture.failedFuture(e);
        }
        onOutbox(stored, (fields, error) -> {
            boolean latest = calls.finish(attempt);
            if (calls.unfinished == 0) {
                pendingStores.remove(key, calls);
            }
            if (!latest) {
                finishFirst(true);
                return;
            }
            onStored(routing, playerId, battleId, bytes, fields, error);
        });
    }

    private void onStored(BattleRouting routing, long playerId, long battleId, ByteString payload, Long fields, Throwable error) {
        boolean unconfirmed = error != null || fields == null || (fields < 1 && fields != BattleRedis.STORE_ALREADY_SETTLED);
        if (unconfirmed) {
            metrics.settlement(SettlementEvent.NOT_DURABLE);
            log.error("结算持久性未确认，首投并登记原件探测；不能将异常视为回滚 battle_id={} player={}: {}", Long.toUnsignedString(battleId),
                    Long.toUnsignedString(playerId), error != null ? error.toString() : "无效落库结果: " + fields);
        }
        if (!unconfirmed && fields == BattleRedis.STORE_ALREADY_SETTLED) {
            // 这一局已经销账（落库的重发 / 晚到落在 scene 的 ACK 之后，脚本没有写入）：按已销账处理，不登记、不投递
            metrics.settlement(SettlementEvent.ALREADY_SETTLED);
            log.info("结算落库时这一局已销账（重放 / 晚到的落库被墓碑挡下），不登记、不投递 battle_id={} player={}",
                    Long.toUnsignedString(battleId), Long.toUnsignedString(playerId));
            finishFirst(true);
            return;
        }
        if (!unconfirmed) {
            metrics.settlement(SettlementEvent.STORED);
        }
        if (!unconfirmed && fields > BattleRedis.SETTLEMENT_FIELDS_WARN) {
            metrics.settlement(SettlementEvent.FIELDS_OVERFLOW);
            log.error("玩家的待结算记录字段数异常（正常为 1） player={} fields={} battle_id={}", Long.toUnsignedString(playerId), fields,
                    Long.toUnsignedString(battleId));
        }
        Key key = new Key(battleId, playerId);
        if (!closed) {
            entries.put(key, new Entry(playerId, battleId, payload, routing.getSceneNodeId(), clockMillis.getAsLong(), unconfirmed));
            metrics.entries(entries.size());
            ensureTimer();
        }
        locateAndDeliver(playerId, battleId, payload, 0, true);
    }

    /** 首投：定位 → 投递（解析不到就不投）。{@code first} 时完成后把在途计数减一。 */
    private void locateAndDeliver(long playerId, long battleId, ByteString payload, int attempt, boolean first) {
        CompletableFuture<Resolution> located;
        try {
            located = locator.locate(playerId);
        } catch (RuntimeException e) {
            located = CompletableFuture.failedFuture(e);
        }
        onOutbox(located, (resolution, error) -> {
            if (error != null || resolution == null || resolution instanceof Failure) {
                metrics.settlement(SettlementEvent.LOCATE_ERROR);
                finishFirst(first);
                return;
            }
            if (resolution instanceof Found found) {
                deliver(found.endpoint(), playerId, battleId, payload, attempt, first);
                return;
            }
            // NoHolder：离线玩家的常态，这次不投，交给下一轮（或进场恢复）
            finishFirst(first);
        });
    }

    private void deliver(SceneAssetEndpoint endpoint, long playerId, long battleId, ByteString payload, int attempt, boolean first) {
        SceneBattleCall call = SceneBattleCall.newBuilder()
                .setTargetInstanceId(endpoint.instanceId())
                .setPlayerId(playerId)
                .setBody(payload)
                .setAttempt(attempt)
                .build();
        // delivered = 发出次数：首投与每次重投都计（重投另计 resend），首投数 = delivered − resend
        metrics.settlement(SettlementEvent.DELIVERED);
        CompletableFuture<SceneBattleReply> sent;
        try {
            sent = transport.applySettlement(endpoint, call);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        if (sent == null) {
            sent = CompletableFuture.failedFuture(new IllegalStateException("结算传输返回了空的 future"));
        }
        sent.whenComplete((reply, error) -> {
            // D16：应答只计数，不改名单（可以在任意线程上计）
            metrics.delivery(deliveryResult(reply, error));
            if (error != null && log.isDebugEnabled()) {
                log.debug("结算投递传输失败（等下一轮重投） battle_id={} player={} node={}: {}", Long.toUnsignedString(battleId),
                        Long.toUnsignedString(playerId), endpoint.nodeId(), error.toString());
            }
            finishFirst(first);
        });
    }

    private void finishFirst(boolean first) {
        if (first) {
            inFlight.decrementAndGet();
        }
    }

    static OutboxMetrics.Delivery deliveryResult(SceneBattleReply reply, Throwable error) {
        if (error != null || reply == null) {
            return OutboxMetrics.Delivery.TRANSPORT_ERROR;
        }
        return switch (reply.getStatus()) {
            case SCENE_BATTLE_HANDLED -> switch (reply.getSettlement()) {
                case SETTLEMENT_APPLIED -> OutboxMetrics.Delivery.APPLIED;
                case SETTLEMENT_ALREADY_APPLIED -> OutboxMetrics.Delivery.ALREADY_APPLIED;
                case SETTLEMENT_DISCARDED -> OutboxMetrics.Delivery.DISCARDED;
                case SETTLEMENT_DISPOSITION_UNSPECIFIED, UNRECOGNIZED -> OutboxMetrics.Delivery.TRANSPORT_ERROR;
            };
            case SCENE_BATTLE_NOT_HERE -> OutboxMetrics.Delivery.NOT_HERE;
            case SCENE_BATTLE_DEFERRED -> OutboxMetrics.Delivery.DEFERRED;
            case SCENE_BATTLE_OVERLOADED -> OutboxMetrics.Delivery.OVERLOADED;
            case SCENE_BATTLE_STATUS_UNSPECIFIED, UNRECOGNIZED -> OutboxMetrics.Delivery.TRANSPORT_ERROR;
        };
    }

    // ------------------------------------------------------------------ 重投（发件箱线程）

    private void ensureTimer() {
        if (timer == null && !entries.isEmpty()) {
            timer = outbox.every(BattleRedis.SETTLEMENT_RETRY_INTERVAL.toMillis(), this::tick);
        }
    }

    /** 一轮（每 10 s）。 */
    void tick() {
        long now = clockMillis.getAsLong();
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (now - entry.enqueuedAtMs > BattleRedis.OUTBOX_MAX_AGE.toMillis()) {
                remove(entry);
                metrics.settlement(SettlementEvent.EXPIRED);
                log.error("结算发件箱条目超龄，停止内存重投；销账未确认，须核对持久原件与消费回执 persistence_unconfirmed={} battle_id={} player={}",
                        entry.persistenceUnconfirmed,
                        Long.toUnsignedString(entry.battleId), Long.toUnsignedString(entry.playerId));
                continue;
            }
            if (entry.probing) {
                continue;
            }
            entry.probing = true;
            CompletableFuture<Boolean> probe;
            try {
                probe = store.exists(entry.playerId, entry.battleId);
            } catch (RuntimeException e) {
                probe = CompletableFuture.failedFuture(e);
            }
            onOutbox(probe, (exists, error) -> onProbe(entry, exists, error));
        }
        stopTimerIfIdle();
    }

    private void onProbe(Entry entry, Boolean exists, Throwable error) {
        if (!registered(entry)) {
            return;
        }
        entry.probing = false;
        if (error != null || exists == null) {
            // D15：出错不是「已销账」，本轮跳过、不计次；没有结论（空结果）同样不是——生产的 Store 已把空回复变成异常，这里再防一层
            metrics.settlement(SettlementEvent.PROBE_ERROR);
            return;
        }
        if (!exists) {
            if (entry.persistenceUnconfirmed) {
                // 未确认的写可能未落地、已销账，或仍在路上。HEXISTS=0 不证明哪一种，也不补写。
                metrics.settlement(SettlementEvent.PROBE_ERROR);
                return;
            }
            remove(entry);
            metrics.settlement(SettlementEvent.ACKED);
            // 最常见的收尾（scene 已销账）：名单空了当场停表，不多空转一轮（基线 room.cpp:1782-1783）
            stopTimerIfIdle();
            return;
        }
        if (entry.persistenceUnconfirmed) {
            verifyOriginal(entry);
            return;
        }
        locateRegistered(entry);
    }

    private void verifyOriginal(Entry entry) {
        entry.probing = true;
        CompletableFuture<byte[]> original;
        try {
            original = store.readOriginal(entry.playerId, entry.battleId);
        } catch (RuntimeException error) {
            original = CompletableFuture.failedFuture(error);
        }
        onOutbox(original, (bytes, error) -> {
            if (!registered(entry)) {
                return;
            }
            entry.probing = false;
            if (error != null || bytes == null || !entry.payload.equals(ByteString.copyFrom(bytes))) {
                metrics.settlement(SettlementEvent.PROBE_ERROR);
                return;
            }
            // 每轮均重读原字节；一次存在证明不把写入未知永久升级为已销账可判定。
            locateRegistered(entry);
        });
    }

    private void locateRegistered(Entry entry) {
        entry.probing = true;
        CompletableFuture<Resolution> located;
        try {
            located = locator.locate(entry.playerId);
        } catch (RuntimeException e) {
            located = CompletableFuture.failedFuture(e);
        }
        onOutbox(located, (resolution, locateError) -> onLocated(entry, resolution, locateError));
    }

    private void onLocated(Entry entry, Resolution resolution, Throwable error) {
        if (!registered(entry)) {
            return;
        }
        entry.probing = false;
        if (error != null || resolution == null || resolution instanceof Failure) {
            metrics.settlement(SettlementEvent.LOCATE_ERROR);
            return;
        }
        boolean hasTarget = resolution instanceof Found;
        // stillOurs 恒为 true：探测那一跳已确认记录还在（同基线 room.cpp:1805），下面的 DONE 分支在这条路径上走不到，留着只为判定表完整。
        // 「已销账排在用尽之前」在这里由 onProbe 的次序保证——先探测、不在就摘，次数已满也一样；不得改成先按次数判用尽
        SettlementRetryRules.Decision decision = SettlementRetryRules.classify(true, entry.attempts, hasTarget);
        entry.attempts++;
        switch (decision) {
            case RESEND -> {
                metrics.settlement(SettlementEvent.RESEND);
                deliver(((Found) resolution).endpoint(), entry.playerId, entry.battleId, entry.payload, entry.attempts, false);
            }
            case SKIP_NO_TARGET -> {
                metrics.settlement(SettlementEvent.SKIP_NO_TARGET);
                // 兼容销账脚本的 3 仅代表字段不存在；未知写入不得借它伪造销账，也不据新锁删原件。
                if (!entry.persistenceUnconfirmed) {
                    ackIfSuperseded(entry);
                }
            }
            case EXHAUSTED -> {
                remove(entry);
                if (hasTarget) {
                    metrics.settlement(SettlementEvent.EXHAUSTED);
                    log.error("结算重投用尽（玩家在线却未确认销账），停止内存重投，未删除 Redis 原件 battle_id={} player={} 开局节点={}",
                            Long.toUnsignedString(entry.battleId), Long.toUnsignedString(entry.playerId), entry.originalSceneNode);
                } else {
                    metrics.settlement(SettlementEvent.EXHAUSTED_OFFLINE);
                    log.warn("结算重投用尽（玩家离线），停止内存重投，未删除 Redis 原件 battle_id={} player={}", Long.toUnsignedString(entry.battleId),
                            Long.toUnsignedString(entry.playerId));
                }
            }
            case DONE -> {
                remove(entry);
                metrics.settlement(SettlementEvent.ACKED);
            }
        }
        stopTimerIfIdle();
    }

    private void ackIfSuperseded(Entry entry) {
        entry.probing = true;
        CompletableFuture<Long> result;
        try {
            result = store.ackIfSuperseded(entry.playerId, entry.battleId);
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        onOutbox(result, (verdict, error) -> {
            if (!registered(entry)) {
                return;
            }
            entry.probing = false;
            if (error != null) {
                metrics.settlement(SettlementEvent.PROBE_ERROR);
                return;
            }
            if (verdict != null && verdict == 2) {
                remove(entry);
                metrics.settlement(SettlementEvent.SUPERSEDED);
                log.info("离线玩家的结算已被下一局取代，摘除（记录已删） battle_id={} player={}", Long.toUnsignedString(entry.battleId),
                        Long.toUnsignedString(entry.playerId));
            } else if (verdict != null && verdict == 3) {
                remove(entry);
                metrics.settlement(SettlementEvent.ACKED);
            }
            stopTimerIfIdle();
        });
    }

    private boolean registered(Entry entry) {
        return entries.get(new Key(entry.battleId, entry.playerId)) == entry;
    }

    private void remove(Entry entry) {
        entries.remove(new Key(entry.battleId, entry.playerId), entry);
        metrics.entries(entries.size());
    }

    private void stopTimerIfIdle() {
        if (entries.isEmpty() && timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 当前登记的条目数（发件箱线程上读；测试用）。 */
    int size() {
        return entries.size();
    }

    /** 在途的落库 + 首投数（任意线程）。 */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * 停机（{@code BattleNode} 停止之后，逻辑线程已停）：最多等 {@code drainTimeout} 让在途的落库与首投回来，然后丢弃内存名单。
     * 已确认的原件由 scene 恢复，未知写入仍须核对持久证据。之后的 {@link #dispatch} 只打 ERROR。
     */
    public void drainAndClose(Duration drainTimeout) {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        closed = true;
        int left = inFlight.get();
        if (left > 0) {
            log.warn("结算发件箱停机：{} 内仍有 {} 份结算的落库 / 首投没回来（落库成功的由 scene 兜底）", drainTimeout, left);
        }
        try {
            outbox.execute(() -> {
                if (!entries.isEmpty()) {
                    log.info("结算发件箱停机，丢弃 {} 条内存登记；未确认销账，须核对持久原件与消费回执", entries.size());
                }
                entries.clear();
                metrics.entries(0);
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 发件箱线程已停
        }
    }

    /** 异步结局投递回发件箱线程（已停时丢弃）。端口返回空 future 按出错处理（不让空指针打断这一条的后续步骤）。 */
    private <T> void onOutbox(CompletableFuture<T> future, java.util.function.BiConsumer<T, Throwable> callback) {
        if (future == null) {
            future = CompletableFuture.failedFuture(new IllegalStateException("端口返回了空的 future"));
        }
        future.whenComplete((value, error) -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            try {
                outbox.execute(() -> callback.accept(value, cause));
            } catch (RejectedExecutionException e) {
                log.debug("发件箱线程已停，丢弃一个异步结局");
            }
        });
    }

    /** 测试 / 排障：当前登记（battle_id → attempts）。 */
    Map<Long, Integer> attemptsByBattle() {
        Map<Long, Integer> out = new LinkedHashMap<>();
        entries.values().forEach(e -> out.put(e.battleId, e.attempts));
        return out;
    }

    /** 测试用：登记里的玩家与局。 */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        entries.values().forEach(e -> out.add(Long.toUnsignedString(e.playerId) + "/" + Long.toUnsignedString(e.battleId) + "#"
                + e.attempts));
        return out;
    }
}
