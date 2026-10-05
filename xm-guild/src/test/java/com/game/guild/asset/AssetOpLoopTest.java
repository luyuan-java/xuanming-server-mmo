package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.guild.asset.AssetOpCaller.Delivery;
import com.game.guild.asset.AssetOpProcessor.Processed;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.player.store.asset.PersistedAssetLedgerReader.Read;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpRejectionState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 重投循环（照基线 reconcile_test.go / reconcile_budget_test.go；guild-economy-spec §11.2 AssetOpLoopTest）：过截止改发 Abort；落库在父预算耗尽后
 * 仍可用；租约丢失只计数；离线读只在 attempts ≥ 3 且本地 NOT_HERE 时发生、读到未见不终结、读到结论直接终结；27007 粘性；坏流号按 ALERT 且不覆盖
 * 答复列；Tick 并发不超过 workers；毒行跳过；E12 传输失败不覆盖 last_outcome；E10 抖动。
 */
class AssetOpLoopTest {

    static final long NOW = 1_700_000_100_000L;
    static final long BASE_MS = 1_000;
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    static final AssetBundle GOLD = AssetBundle.newBuilder()
            .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(100)).build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GuildMetrics metrics = new GuildMetrics(meters);
    private final InMemoryAssetStore store = new InMemoryAssetStore();
    private final AtomicLong clock = new AtomicLong(NOW);
    private final Deque<Supplier<CompletableFuture<Delivery>>> deliveries = new ArrayDeque<>();
    private final List<AssetRpc> rpcs = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> budgets = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private volatile Read ledgerRead = new Read.Absent();
    private final AtomicInteger ledgerReads = new AtomicInteger();
    private volatile double jitter = 0.5;

    @AfterEach
    void close() {
        workers.shutdownNow();
    }

    private AssetOpLoop loop(int workerCount, java.util.concurrent.Executor settle) {
        AssetOpLoop.Config config = new AssetOpLoop.Config(Duration.ofSeconds(2), 100, workerCount, Duration.ofSeconds(10),
                Duration.ofMillis(2500), BASE_MS, MAX_BACKOFF, Duration.ofHours(1), 3);
        PersistedLedgerReader ledger = new PersistedLedgerReader(playerId -> {
            ledgerReads.incrementAndGet();
            return ledgerRead;
        });
        return new AssetOpLoop(config, store, this::deliver, ledger, metrics, clock::get, workers, settle, new Random(7),
                () -> jitter);
    }

    private AssetOpLoop loop() {
        return loop(4, Runnable::run);
    }

    private CompletableFuture<Delivery> deliver(AssetRpc rpc, com.game.api.proto.AssetOpRequest request, Deadline budget) {
        rpcs.add(rpc);
        budgets.add(budget.remainingMillis());
        Supplier<CompletableFuture<Delivery>> next;
        synchronized (deliveries) {
            next = deliveries.size() > 1 ? deliveries.poll() : deliveries.peek();
        }
        return next == null ? CompletableFuture.completedFuture(Delivery.failed(new AssetDeliveryException("unscripted"))) : next.get();
    }

    private void reply(AssetOutcome outcome, boolean durable, int reason) {
        deliveries.add(() -> CompletableFuture.completedFuture(Delivery.answered(
                new AssetOpResult(outcome, reason, durable, false, false))));
    }

    private void reply(Delivery delivery) {
        deliveries.add(() -> CompletableFuture.completedFuture(delivery));
    }

    private AssetOp claimOne(GuildAssetOpRow row) {
        store.insert(row);
        return loop().claim(row.getOpId()).orElseThrow();
    }

    private double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    // ================================================================ 方向

    @Test
    void 过了截止改发Abort_回占位拒绝终结为ABORTED() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).setDeadlineMs(NOW - 1).build());
        reply(AssetOutcome.ASSET_OUTCOME_REJECTED, true, 0);
        Processed p = loop().processClaimed(op);
        assertThat(rpcs).containsExactly(AssetRpc.ABORT_DEBIT);
        assertThat(p.finalized()).isTrue();
        assertThat(p.status()).isEqualTo(AssetOpStatus.ABORTED);
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED);
        assertThat(store.row(1).getNextAttemptMs()).as("终态行 next_attempt_ms = 终结时刻").isEqualTo(NOW);
        assertThat(count("xm.guild.assetop.finalize", "stream", "guild_debit", "status", "aborted")).isEqualTo(1);
    }

    @Test
    void 截止未到按流方向发_扣款流发Debit_发放流发Credit() {
        AssetOp debit = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).setDeadlineMs(NOW + 60_000).build());
        AssetOp credit = claimOne(InMemoryAssetStore.pending(2, 11, 1, GOLD).setStream(2).setTxType(25).build());
        reply(AssetOutcome.ASSET_OUTCOME_APPLIED, true, 0);
        loop().processClaimed(debit);
        loop().processClaimed(credit);
        assertThat(rpcs).containsExactly(AssetRpc.DEBIT, AssetRpc.CREDIT);
        assertThat(budgets).allSatisfy(b -> assertThat(b).isLessThanOrEqualTo(1_800L));
    }

    @Test
    void 坏流号按ALERT重排_不猜方向_不覆盖答复列() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).setStream(9).setLastOutcome(3).setLastReason(27001)
                .build());
        Processed p = loop().processClaimed(op);
        assertThat(rpcs).isEmpty();
        assertThat(p.finalized()).isFalse();
        GuildAssetOpRow row = store.row(1);
        assertThat(row.getNextAttemptMs()).isEqualTo(NOW + MAX_BACKOFF.toMillis());
        assertThat(row.getLastOutcome()).isEqualTo(3);
        assertThat(row.getLastReason()).isEqualTo(27001);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(count("xm.guild.assetop.unknown", "stream", "other")).isEqualTo(1);
        assertThat(count("xm.guild.assetop.reschedule", "stream", "other", "reason", "alert")).isEqualTo(1);
    }

    // ================================================================ Decide 各分支

    @Test
    void 传输失败按退避重投_E12不覆盖last_outcome() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).setLastOutcome(AssetOutcome.ASSET_OUTCOME_RETRY_VALUE)
                .setLastReason(AssetOpDecisions.REASON_BAG_FULL).setDurable(0).build());
        reply(Delivery.failed(new AssetDeliveryException("conn refused")));
        jitter = 0;
        loop().processClaimed(op);
        GuildAssetOpRow row = store.row(1);
        assertThat(row.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY_VALUE);
        assertThat(row.getLastReason()).isEqualTo(AssetOpDecisions.REASON_BAG_FULL);
        assertThat(row.getAttempts()).isEqualTo(1);
        // E10：attempts 0、base 1 s、抖动 0 → 0.8 s
        assertThat(row.getNextAttemptMs()).isEqualTo(NOW + 800);
        assertThat(row.getLeaseUntilMs()).isZero();
        assertThat(store.journal).anyMatch(j -> j.startsWith("rescheduleWithoutAnswer 1"));
        assertThat(count("xm.guild.assetop.reschedule", "stream", "guild_debit", "reason", "retry")).isEqualTo(1);
    }

    @Test
    void 退避抖动正负20百分比() {
        assertThat(AssetOpDecisions.nextAttemptMs(NOW, 2, BASE_MS, MAX_BACKOFF.toMillis(), () -> 0.0)).isEqualTo(NOW + 3_200);
        assertThat(AssetOpDecisions.nextAttemptMs(NOW, 2, BASE_MS, MAX_BACKOFF.toMillis(), () -> 0.5)).isEqualTo(NOW + 4_000);
        assertThat(AssetOpDecisions.nextAttemptMs(NOW, 2, BASE_MS, MAX_BACKOFF.toMillis(), () -> 0.9999)).isBetween(NOW + 4_799,
                NOW + 4_800);
    }

    @Test
    void 有结局但未落盘_500毫秒后用同一seq再查() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        reply(AssetOutcome.ASSET_OUTCOME_APPLIED, false, 0);
        loop().processClaimed(op);
        GuildAssetOpRow row = store.row(1);
        assertThat(row.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(row.getNextAttemptMs()).isEqualTo(NOW + 500);
        assertThat(row.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED_VALUE);
        assertThat(count("xm.guild.assetop.reschedule", "stream", "guild_debit", "reason", "await_durable")).isEqualTo(1);
    }

    @Test
    void UNKNOWN按ALERT_最大退避后再来_永不终结() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        reply(AssetOutcome.ASSET_OUTCOME_UNKNOWN, false, AssetOpDecisions.REASON_AUTH_FAILED);
        loop().processClaimed(op);
        GuildAssetOpRow row = store.row(1);
        assertThat(row.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(row.getNextAttemptMs()).isEqualTo(NOW + MAX_BACKOFF.toMillis());
        assertThat(row.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN_VALUE);
        assertThat(count("xm.guild.assetop.unknown", "stream", "guild_debit")).isEqualTo(1);
        assertThat(count("xm.guild.assetop.reschedule", "stream", "guild_debit", "reason", "alert")).isEqualTo(1);
    }

    @Test
    void 结局翻转按ALERT_写下最新答复() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        reply(new Delivery(new AssetOpResult(AssetOutcome.ASSET_OUTCOME_REJECTED, 27000, true, false, false),
                new AssetOutcomeFlipException("flip")));
        Processed p = loop().processClaimed(op);
        assertThat(p.finalized()).isFalse();
        assertThat(store.row(1).getNextAttemptMs()).isEqualTo(NOW + MAX_BACKOFF.toMillis());
        assertThat(store.row(1).getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED_VALUE);
        assertThat(count("xm.guild.assetop.unknown", "stream", "guild_debit")).as("翻转由调用方计 outcome_flip，不重复计 unknown")
                .isZero();
    }

    @Test
    void 部分发放码在行上是粘性的_重排强制写27007_终结落APPLIED_PARTIAL() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).setStream(2)
                .setLastReason(AssetOpDecisions.REASON_PARTIAL_APPLIED).build());
        reply(AssetOutcome.ASSET_OUTCOME_RETRY, false, AssetOpDecisions.REASON_BAG_FULL);
        loop().processClaimed(op);
        assertThat(store.row(1).getLastReason()).isEqualTo(AssetOpDecisions.REASON_PARTIAL_APPLIED);

        op = loop().claim(1).orElseThrow(() -> new AssertionError("租约已清，应能再领"));
        clock.addAndGet(60_000);
        op = loop().claim(1).orElse(op);
        deliveries.clear();
        reply(AssetOutcome.ASSET_OUTCOME_APPLIED, true, 0);
        Processed p = loop().processClaimed(op);
        assertThat(p.status()).isEqualTo(AssetOpStatus.APPLIED_PARTIAL);
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL);
    }

    @Test
    void 租约丢失只计数_不报错() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        // 别的副本在租约过期后领走：令牌换了
        store.rows.put(1L, store.row(1).toBuilder().setLeaseToken(12345).build());
        reply(AssetOutcome.ASSET_OUTCOME_RETRY, false, 0);
        Processed p = loop().processClaimed(op);
        assertThat(p.finalized()).isFalse();
        assertThat(count("xm.guild.assetop.reschedule.lost", "stream", "guild_debit")).isEqualTo(1);
        assertThat(count("xm.guild.assetop.reschedule", "stream", "guild_debit", "reason", "retry")).isZero();
    }

    @Test
    void 落库失败计store_errors_行留在PENDING() {
        AssetOp op = claimOne(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        reply(AssetOutcome.ASSET_OUTCOME_APPLIED, true, 0);
        store.failure = new Deadline.DependencyException("mysql down");
        Processed p = loop().processClaimed(op);
        assertThat(p.finalized()).isFalse();
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(count("xm.guild.assetop.store.errors", "op", "finalize")).isEqualTo(1);
    }

    // ================================================================ 离线读已落盘账本（E8）

    private static Read ledgerWith(long epoch, long appliedSeq, long rejectedSeq, int reason, boolean partial) {
        long[] seen = new long[16];
        long[] applied = new long[16];
        AssetOpStreamLedgerState.Builder s = AssetOpStreamLedgerState.newBuilder().setStream(1).setWatermark(0)
                .setStreamEpoch(epoch).setMaxSeq(Math.max(appliedSeq, rejectedSeq));
        if (appliedSeq > 0) {
            seen[0] |= 1L << (appliedSeq - 1);
            applied[0] |= 1L << (appliedSeq - 1);
            if (partial) {
                s.addPartialSeqs(appliedSeq);
            }
        }
        if (rejectedSeq > 0) {
            seen[0] |= 1L << (rejectedSeq - 1);
            if (reason != 0) {
                s.addRejections(AssetOpRejectionState.newBuilder().setSeq(rejectedSeq).setReasonTipId(reason));
            }
        }
        for (int i = 0; i < 16; i++) {
            s.addSeenBits(seen[i]);
            s.addAppliedBits(applied[i]);
        }
        return new Read.Loaded(PersistedAssetLedger.restore(AssetOpLedgerState.newBuilder().addStreams(s).build()));
    }

    private AssetOp offlineOp(long opId, long seq, int attempts) {
        return claimOne(InMemoryAssetStore.pending(opId, 11, seq, GOLD).setAttempts(attempts).build());
    }

    @Test
    void 离线读只在本地NOT_HERE且尝试够3次时发生() {
        reply(Delivery.answered(AssetOpResult.localNotHere()));
        ledgerRead = ledgerWith(1_700_000_000_000L, 1, 0, 0, false);
        loop().processClaimed(offlineOp(1, 1, 2));
        assertThat(ledgerReads).hasValue(0);
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);

        // scene 回的 NOT_HERE（不是本地合成）不读
        deliveries.clear();
        reply(AssetOutcome.ASSET_OUTCOME_NOT_HERE, false, AssetOpDecisions.REASON_PLAYER_NOT_HERE);
        loop().processClaimed(offlineOp(2, 1, 5));
        assertThat(ledgerReads).hasValue(0);

        deliveries.clear();
        reply(Delivery.answered(AssetOpResult.localNotHere()));
        Processed p = loop().processClaimed(offlineOp(3, 1, 3));
        assertThat(ledgerReads).hasValue(1);
        assertThat(p.finalized()).isTrue();
        assertThat(p.status()).isEqualTo(AssetOpStatus.APPLIED);
        assertThat(store.row(3).getDurable()).isEqualTo(1);
        assertThat(count("xm.guild.assetop.ledger.read", "result", "finalized")).isEqualTo(1);
    }

    @Test
    void 离线读到拒绝与部分发放都直接终结() {
        reply(Delivery.answered(AssetOpResult.localNotHere()));
        ledgerRead = ledgerWith(1_700_000_000_000L, 0, 2, 27000, false);
        Processed p = loop().processClaimed(offlineOp(1, 2, 3));
        assertThat(p.status()).isEqualTo(AssetOpStatus.REJECTED);
        assertThat(store.row(1).getReasonTipId()).isEqualTo(27000);

        ledgerRead = ledgerWith(1_700_000_000_000L, 3, 0, 0, true);
        p = loop().processClaimed(offlineOp(2, 3, 4));
        assertThat(p.status()).isEqualTo(AssetOpStatus.APPLIED_PARTIAL);
    }

    @Test
    void 离线读到未见_旧纪元_没有行_读失败_损坏_都不终结() {
        reply(Delivery.answered(AssetOpResult.localNotHere()));
        // 未见
        ledgerRead = ledgerWith(1_700_000_000_000L, 1, 0, 0, false);
        assertThat(loop().processClaimed(offlineOp(1, 5, 3)).finalized()).isFalse();
        // 旧纪元（账本纪元更大：这一 seq 的结局不可采信）
        ledgerRead = ledgerWith(1_800_000_000_000L, 1, 0, 0, false);
        assertThat(loop().processClaimed(offlineOp(2, 1, 3)).finalized()).isFalse();
        assertThat(count("xm.guild.assetop.ledger.read", "result", "unseen")).isEqualTo(2);
        // 没有 player_state 行
        ledgerRead = new Read.Absent();
        assertThat(loop().processClaimed(offlineOp(3, 1, 3)).finalized()).isFalse();
        assertThat(count("xm.guild.assetop.ledger.read", "result", "absent")).isEqualTo(1);
        // 读失败、账本损坏
        ledgerRead = new Read.Failed("timeout", null);
        assertThat(loop().processClaimed(offlineOp(4, 1, 3)).finalized()).isFalse();
        ledgerRead = new Read.Loaded(PersistedAssetLedger.restore(AssetOpLedgerState.newBuilder()
                .addStreams(AssetOpStreamLedgerState.newBuilder().setStream(1).setStreamEpoch(1)).build()));
        assertThat(loop().processClaimed(offlineOp(5, 1, 3)).finalized()).isFalse();
        assertThat(count("xm.guild.assetop.ledger.read", "result", "error")).isEqualTo(2);
        assertThat(count("xm.guild.assetop.store.errors", "op", "ledger_read")).isEqualTo(2);
        // 都按 RETRY 退避（本地 NOT_HERE 是答复：覆盖答复列）
        assertThat(store.row(1).getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE_VALUE);
    }

    // ================================================================ 领取 / Tick

    @Test
    void 毒行跳过_计poison与decode() {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).setPayload(ByteString.copyFromUtf8("ÿÿ garbage"))
                .build());
        assertThat(loop().claim(1)).isEmpty();
        assertThat(store.row(1).getNextAttemptMs()).isEqualTo(NOW + Duration.ofHours(1).toMillis());
        assertThat(count("xm.guild.assetop.claim", "result", "poison")).isEqualTo(1);
        assertThat(count("xm.guild.assetop.store.errors", "op", "decode")).isEqualTo(1);
    }

    @Test
    void 领取后被人工终结抢先_不下发() {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        store.afterClaimCas = id -> store.rows.put(id, store.row(id).toBuilder()
                .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED).build());
        assertThat(loop().claim(1)).isEmpty();
        assertThat(count("xm.guild.assetop.claim", "result", "lost")).isEqualTo(1);
    }

    @Test
    void Tick并发不超过workers_每行恰好处理一次() throws Exception {
        for (long id = 1; id <= 20; id++) {
            store.insert(InMemoryAssetStore.pending(id, 100 + id, 1, GOLD).build());
        }
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        ExecutorService scene = Executors.newCachedThreadPool();
        deliveries.add(() -> CompletableFuture.supplyAsync(() -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return Delivery.answered(new AssetOpResult(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true, false, false));
        }, scene));
        AssetOpLoop loop = loop(3, Runnable::run);
        loop.start();
        try {
            assertThat(loop.tick()).isEqualTo(20);
        } finally {
            loop.stop(Duration.ofSeconds(5));
            scene.shutdownNow();
        }
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(3);
        assertThat(store.finalized).hasSize(20);
        assertThat(store.finalized).extracting(GuildAssetStore.FinalizedOp::origin).containsOnly(DeliveryOrigin.LOOP);
        assertThat(store.rows.values()).allMatch(r -> r.getStatus() == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
    }

    @Test
    void 停止后Tick不再领行() {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).build());
        AssetOpLoop loop = loop();
        assertThat(loop.tick()).as("没启动").isZero();
        loop.start();
        loop.stop(Duration.ofSeconds(1));
        assertThat(loop.tick()).isZero();
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
    }

    // ================================================================ 同步路径

    @Test
    void 同步路径_投递跑满预算后落库仍有自己的预算_终结带SYNC() throws Exception {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).setLeaseToken(99).setLeaseUntilMs(NOW + 10_000).build());
        AssetOp op = new AssetOp(1, 11, 1, 1, 1_700_000_000_000L, 1, 24, GOLD, 0, NOW + 600_000, 99, 0);
        ExecutorService scene = Executors.newSingleThreadExecutor();
        deliveries.add(() -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(150); // 比给的预算（100 ms）还长
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Delivery.answered(new AssetOpResult(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true, false, false));
        }, scene));
        ExecutorService settle = Executors.newSingleThreadExecutor();
        try {
            Processed p = loop(4, settle).processOne(op, DeliveryOrigin.SYNC, 100).get(5, TimeUnit.SECONDS);
            assertThat(p.finalized()).isTrue();
            assertThat(budgets.getFirst()).isLessThanOrEqualTo(100L);
            assertThat(store.finalized).extracting(GuildAssetStore.FinalizedOp::origin).containsExactly(DeliveryOrigin.SYNC);
        } finally {
            scene.shutdownNow();
            settle.shutdownNow();
        }
    }

    @Test
    void 同步路径_落库执行器满了不落库_计settle_rejected() throws Exception {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).setLeaseToken(99).build());
        AssetOp op = new AssetOp(1, 11, 1, 1, 1_700_000_000_000L, 1, 24, GOLD, 0, NOW + 600_000, 99, 0);
        reply(AssetOutcome.ASSET_OUTCOME_APPLIED, true, 0);
        Processed p = loop(4, task -> {
            throw new RejectedExecutionException("full");
        }).processOne(op, DeliveryOrigin.SYNC, 1_000).get(5, TimeUnit.SECONDS);
        assertThat(p.finalized()).isFalse();
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(count("xm.guild.assetop.store.errors", "op", "settle_rejected")).isEqualTo(1);
    }

    @Test
    void 同步路径_落库在执行器里排队太久_开工时已过最晚点就不落库_计settle_late_留给循环() throws Exception {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).setLeaseToken(99).setLeaseUntilMs(NOW + 10_000).build());
        AssetOp op = new AssetOp(1, 11, 1, 1, 1_700_000_000_000L, 1, 24, GOLD, 0, NOW + 600_000, 99, 0);
        reply(AssetOutcome.ASSET_OUTCOME_APPLIED, true, 0);
        // 落库执行器排队：任务晚于「投递预算 + 300 ms」才开工
        long lateMs = 50 + AssetOpLoop.SYNC_SETTLE_START_SLACK_MS + 200;
        java.util.concurrent.Executor slowQueue = task -> CompletableFuture.delayedExecutor(lateMs, TimeUnit.MILLISECONDS)
                .execute(task);
        Processed p = loop(4, slowQueue).processOne(op, DeliveryOrigin.SYNC, 50).get(5, TimeUnit.SECONDS);
        assertThat(p.finalized()).isFalse();
        assertThat(store.row(1).getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(store.finalized).isEmpty();
        assertThat(count("xm.guild.assetop.store.errors", "op", "settle_late")).isEqualTo(1);
    }

    // ================================================================ 最老未决年龄

    @Test
    void 最老未决年龄按流刷新_没有未决为0() {
        store.insert(InMemoryAssetStore.pending(1, 11, 1, GOLD).setCreatedMs(NOW - 90_000).build());
        loop().refreshPendingAge();
        assertThat(meters.get("xm.guild.assetop.pending.oldest.age.seconds").tag("stream", "guild_debit").gauge().value())
                .isEqualTo(90.0);
        assertThat(meters.get("xm.guild.assetop.pending.oldest.age.seconds").tag("stream", "guild_credit").gauge().value())
                .isZero();
    }

    @Test
    void 循环参数校验同基线() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AssetOpLoop.Config(Duration.ofSeconds(2), 4, 8,
                Duration.ofSeconds(10), Duration.ofMillis(2500), BASE_MS, MAX_BACKOFF, Duration.ofHours(1), 3))
                .hasMessageContaining("batch");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AssetOpLoop.Config(Duration.ofSeconds(2), 100, 8,
                Duration.ofSeconds(4), Duration.ofMillis(2500), BASE_MS, MAX_BACKOFF, Duration.ofHours(1), 3))
                .hasMessageContaining("lease");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AssetOpLoop.Config(Duration.ofSeconds(2), 100, 8,
                Duration.ofSeconds(10), Duration.ofMillis(700), BASE_MS, MAX_BACKOFF, Duration.ofHours(1), 3))
                .hasMessageContaining("op-budget");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AssetOpLoop.Config(Duration.ofSeconds(2), 100, 8,
                Duration.ofSeconds(10), Duration.ofMillis(2500), 0, MAX_BACKOFF, Duration.ofHours(1), 3))
                .hasMessageContaining("退避");
    }
}
