package com.game.guild.asset;

import com.game.api.asset.AssetRpc;
import com.game.common.deadline.Deadline;
import com.game.guild.asset.AssetOpCaller.Delivery;
import com.game.guild.asset.AssetOpMetrics.ClaimResult;
import com.game.guild.asset.AssetOpMetrics.LedgerReadResult;
import com.game.guild.asset.AssetOpMetrics.StoreOp;
import com.game.guild.asset.GuildAssetStore.Claimed;
import com.game.guild.asset.GuildAssetStore.FinalizeResult;
import com.game.guild.asset.GuildAssetStore.Lost;
import com.game.guild.asset.GuildAssetStore.Poisoned;
import com.game.guild.asset.GuildAssetStore.RescheduleResult;
import com.game.guild.asset.PersistedLedgerReader.Absent;
import com.game.guild.asset.PersistedLedgerReader.Finalized;
import com.game.guild.asset.PersistedLedgerReader.Unreadable;
import com.game.guild.asset.PersistedLedgerReader.Unseen;
import com.game.guild.rules.GuildLimits;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产指令的重投循环（基线 {@code go/shared/assetop/reconcile.go}；guild-economy-spec §2.5–§2.7、§7.4、§7.5）。它只做三件事：把到期的待办行领出来、
 * 投一次、按结论终结或重排。业务副作用（帮会资金、帮贡、退次数 / 限购）全部在 {@link GuildAssetStore#finalizeOp} 的事务里，本类不碰。
 *
 * <p><b>每个副本一个</b>：每 {@code reconcile-interval} 一次 Tick——ListDue（新行优先、老行补缺口）→ 主键 CAS 领取 → 处理 → 落库；
 * {@code min(workers, n)} 条专用 worker（{@code guild-asset-worker}）并发，<b>Tick 等本批全部结束才返回</b>，所以每副本同时在途的资产 RPC
 * ≤ workers；另每 30 s 刷新最老未决行年龄（{@code pending_oldest_age_seconds{stream}}）。
 *
 * <p><b>单行</b>（ProcessOne，reconcile.go:478-633）：截止已过 → 改发 Abort，否则按流的方向；流号非法 → 坏行，ALERT 重排、<b>绝不猜方向</b>。
 * 投递只拿 {@code op-budget − 700 ms}；没发出去（本地 NOT_HERE）且已试过 {@code ledger-read-min-attempts} 次 → 离线读已落盘账本（E8）。
 * 落库（终结 / 重排）另有 700 ms 自有预算、<b>不随请求取消</b>：同步路径的父预算跑满后再拿死预算写库必失败——scene 已扣钱、行仍 PENDING、
 * 下一轮再投一次（reconcile.go:36-56）。
 * <ul>
 *   <li>FINALIZE：{@link AssetOpDecisions#finalStatus}；算出 PENDING = bug，按 ALERT 重排、不写状态列；APPLIED_PARTIAL 打 ERROR 要求人工补偿；</li>
 *   <li>AWAIT_DURABLE：500 ms 后用同一请求再查；</li>
 *   <li>RETRY：指数退避 + <b>±20% 抖动</b>（E10：基线 {@code Loop.Rand} 从未赋值、生产不抖动；Java 按配表与代码注释的设计意图启用）；</li>
 *   <li>ALERT（UNKNOWN / 结局翻转 / 坏流号）：{@code max-backoff} 后再来，永不终结，转人工 assetopfix；</li>
 *   <li>重排前 {@link AssetOpDecisions#carryPartialReason}：行上的 27007 是粘性的；没拿到 scene 答复（传输 / 定位故障、坏流号）的重排
 *       <b>不覆盖</b> last_outcome / last_reason / durable（E12，{@link GuildAssetStore#rescheduleWithoutAnswer}）；租约丢失只计数。</li>
 * </ul>
 *
 * <p><b>线程</b>：Tick 在单条调度线程 {@code guild-asset-reconcile} 上跑，worker 在专用池上阻塞等 Dubbo future 与 JDBC（上限即并发上限，与基线
 * goroutine 等价）；同步路径（{@link #processOne}）全程异步：投递不占线程，离线读与落库在有界的 {@code guild-asset-settle} 执行器上跑。
 * 关停：{@link #stop} 不再领新行，等当前 Tick 的 worker 各自做完手上那一行（落库 700 ms 拦不住，也不该拦）。线程安全。
 */
public final class AssetOpLoop implements AssetOpProcessor {

    private static final Logger log = LoggerFactory.getLogger(AssetOpLoop.class);

    /** 循环节律与预算（基线 LoopConfig，reconcile.go:223-282；缺省与区间由 {@code GuildProperties.AssetOp} 校验）。 */
    public record Config(Duration interval, int batch, int workers, Duration lease, Duration opBudget, long baseBackoffMs,
                         Duration maxBackoff, Duration poisonDelay, int ledgerReadMinAttempts) {

        public Config {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(opBudget, "opBudget");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            Objects.requireNonNull(poisonDelay, "poisonDelay");
            // 同 LoopConfig.validate（reconcile.go:284-320）：一眼能看出的配置错，留到运行时才炸没有任何好处
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("asset-op interval 必须为正: " + interval);
            }
            if (workers < 1 || workers > GuildLimits.ASSET_MAX_WORKERS) {
                throw new IllegalArgumentException("asset-op workers 必须在 [1, " + GuildLimits.ASSET_MAX_WORKERS + "]: " + workers);
            }
            if (batch < workers) {
                throw new IllegalArgumentException("asset-op batch(" + batch + ") 不得小于 workers(" + workers + ")");
            }
            // 预算要同时装下投递和落库：只够落库的预算意味着投递一诞生就过期，循环会空转重试而不报错
            if (opBudget.toMillis() <= GuildLimits.ASSET_SETTLE_BUDGET_MS) {
                throw new IllegalArgumentException("asset-op op-budget(" + opBudget + ") 必须大于落库预留 "
                        + GuildLimits.ASSET_SETTLE_BUDGET_MS + " ms");
            }
            if (opBudget.toMillis() + GuildLimits.ASSET_LEASE_HEADROOM_MS > lease.toMillis()) {
                throw new IllegalArgumentException("asset-op lease(" + lease + ") 必须 >= op-budget(" + opBudget + ") + "
                        + GuildLimits.ASSET_LEASE_HEADROOM_MS + " ms");
            }
            // 退避基数来自配表 GuildRule.asset_op_retry_base_ms：缺行时为 0，构造失败（不带着猜出来的默认值跑）
            if (baseBackoffMs <= 0 || maxBackoff.toMillis() < baseBackoffMs) {
                throw new IllegalArgumentException("asset-op 退避区间非法(base=" + baseBackoffMs + "ms max=" + maxBackoff + ")");
            }
            if (poisonDelay.isNegative() || poisonDelay.isZero()) {
                throw new IllegalArgumentException("asset-op poison-delay 必须为正: " + poisonDelay);
            }
            if (ledgerReadMinAttempts < 1) {
                throw new IllegalArgumentException("asset-op ledger-read-min-attempts 至少为 1: " + ledgerReadMinAttempts);
            }
        }

        /** 投递分到的预算（op-budget − 700 ms）。 */
        long deliveryBudgetMs() {
            return opBudget.toMillis() - GuildLimits.ASSET_SETTLE_BUDGET_MS;
        }
    }

    private final Config config;
    private final GuildAssetStore store;
    private final Applier applier;
    private final PersistedLedgerReader ledger;
    private final AssetOpMetrics metrics;
    private final LongSupplier clockMs;
    private final Executor workers;
    private final Executor settle;
    private final RandomGenerator tokens;
    private final DoubleSupplier jitter;

    private final Object lifecycle = new Object();
    private volatile boolean running;
    /** 持 {@link #lifecycle}。 */
    private ScheduledExecutorService scheduler;

    /**
     * @param ledger  离线读已落盘账本；null = 不读（长期离线玩家的行照常退避，玩家上线后由在线投递终结）
     * @param workers 循环的专用 worker 池（{@code guild-asset-worker}，线程数 = workers）
     * @param settle  同步路径的落库执行器（{@code guild-asset-settle}，有界；满了 → 行留在 PENDING 等租约到期由循环接手）
     * @param tokens  租约令牌的随机源（生产 {@link java.security.SecureRandom}）
     * @param jitter  退避抖动源（生产 {@code ThreadLocalRandom.current()::nextDouble}，E10）
     */
    public AssetOpLoop(Config config, GuildAssetStore store, Applier applier, PersistedLedgerReader ledger,
                       AssetOpMetrics metrics, LongSupplier clockMs, Executor workers, Executor settle,
                       RandomGenerator tokens, DoubleSupplier jitter) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.ledger = ledger;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.settle = Objects.requireNonNull(settle, "settle");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    public Config config() {
        return config;
    }

    /** 是否接了离线读账本（启动日志用）。 */
    public boolean readsPersistedLedger() {
        return ledger != null;
    }

    // ================================================================ 生命周期

    /** 启动 Tick（每 interval）与最老未决年龄刷新（每 30 s）。重复调用是空操作。 */
    public void start() {
        synchronized (lifecycle) {
            if (scheduler != null) {
                return;
            }
            running = true;
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("guild-asset-reconcile").daemon(true).factory());
            long interval = config.interval().toMillis();
            // 固定间隔（上一轮结束后再等 interval）：Tick 等本批 worker 全部结束才返回，不会叠加；基线 Ticker 在慢 Tick 后会补发一次
            scheduler.scheduleWithFixedDelay(() -> guarded("reconcile_tick", this::tick), interval, interval,
                    TimeUnit.MILLISECONDS);
            scheduler.scheduleWithFixedDelay(() -> guarded("pending_age", this::refreshPendingAge),
                    GuildLimits.ASSET_PENDING_AGE_INTERVAL_MS, GuildLimits.ASSET_PENDING_AGE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }
        log.info("[AssetOp] 重投循环已启动 interval={} batch={} workers={} lease={} op_budget={} base_backoff={}ms max_backoff={} "
                        + "poison_delay={} ledger_read={}（min_attempts={}）", config.interval(), config.batch(), config.workers(),
                config.lease(), config.opBudget(), config.baseBackoffMs(), config.maxBackoff(), config.poisonDelay(),
                ledger != null, config.ledgerReadMinAttempts());
    }

    /**
     * 停止：不再领新行，等当前 Tick 的 worker 做完手上那一行（每行至多 op-budget：投递 + 落库，落库 700 ms 不受取消）。幂等。
     *
     * @param timeout 至多等多久（之后中断调度线程）
     */
    public void stop(Duration timeout) {
        ScheduledExecutorService s;
        synchronized (lifecycle) {
            running = false;
            s = scheduler;
            scheduler = null;
        }
        if (s == null) {
            return;
        }
        s.shutdown();
        try {
            if (!s.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[AssetOp] 重投循环 {} 内没有停完，中断调度线程", timeout);
                s.shutdownNow();
            }
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[AssetOp] 重投循环已停止");
    }

    public boolean isRunning() {
        return running;
    }

    /** 每一轮各自兜底：某一轮炸掉只丢那一轮，循环继续（一个补偿循环静默停摆比单轮出错危险得多，reconcile.go:364-384）。 */
    private static void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("[AssetOp] {} 出错（本轮作废，循环继续）", what, t);
        }
    }

    // ================================================================ Tick

    /**
     * 领一批到期行并逐行处理，返回实际处理了多少行（Tick，reconcile.go:386-424）。并发严格受 workers 限制；等所有 worker 结束才返回。
     */
    public int tick() {
        if (!running) {
            return 0;
        }
        List<Long> ids;
        try {
            ids = store.listDue(clockMs.getAsLong(), config.batch(), Deadline.after(GuildLimits.ASSET_STORE_READ_BUDGET_MS));
        } catch (RuntimeException e) {
            metrics.storeError(StoreOp.LIST);
            log.error("[AssetOp] 列出到期行失败: {}", e.toString());
            return 0;
        }
        if (ids.isEmpty()) {
            return 0;
        }
        Queue<Long> queue = new ConcurrentLinkedQueue<>(ids);
        int n = Math.min(config.workers(), ids.size());
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger processed = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            try {
                workers.execute(() -> {
                    try {
                        drain(queue, processed);
                    } finally {
                        done.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                // 只在关停时发生
                done.countDown();
            }
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return processed.get();
    }

    /** 一条 worker 的主体：逐个领取并处理，直到队列空或循环停止。单行失败只记不抛（这一行靠 next_attempt_ms 再来）。 */
    private void drain(Queue<Long> queue, AtomicInteger processed) {
        Long opId;
        while (running && (opId = queue.poll()) != null) {
            Optional<AssetOp> op = claim(opId);
            if (op.isEmpty()) {
                continue;
            }
            try {
                processClaimed(op.get());
            } catch (RuntimeException e) {
                log.error("[AssetOp] 处理待办行失败 op_id={} stream={} seq={}", Long.toUnsignedString(opId), op.get().stream(),
                        Long.toUnsignedString(op.get().seq()), e);
            }
            processed.incrementAndGet();
        }
    }

    /** 领一行（claim，reconcile.go:445-476）。空 = 本轮不处理（被别人领走、已终结、毒行或出错）。 */
    Optional<AssetOp> claim(long opId) {
        long token = AssetOpDecisions.leaseToken(tokens);
        long nowMs = clockMs.getAsLong();
        long leaseUntilMs = nowMs + config.lease().toMillis();
        // 毒行推到什么时候由循环算：时刻、随机源、超时都归循环管，Store 只写给它的数字
        long poisonUntilMs = nowMs + config.poisonDelay().toMillis();
        GuildAssetStore.ClaimResult result;
        try {
            result = store.claim(opId, nowMs, leaseUntilMs, poisonUntilMs, token,
                    Deadline.after(GuildLimits.ASSET_STORE_CLAIM_BUDGET_MS));
        } catch (RuntimeException e) {
            metrics.storeError(StoreOp.CLAIM);
            log.error("[AssetOp] 领取待办行失败 op_id={}: {}", Long.toUnsignedString(opId), e.toString());
            return Optional.empty();
        }
        return switch (result) {
            case Claimed claimed -> {
                metrics.claim(ClaimResult.CLAIMED);
                yield Optional.of(claimed.op());
            }
            case Lost ignored -> {
                metrics.claim(ClaimResult.LOST);
                yield Optional.empty();
            }
            case Poisoned poisoned -> {
                // 毒行：Store 已经把它推远了。这里只计数 + 报位置，人工去看
                metrics.claim(ClaimResult.POISON);
                metrics.storeError(StoreOp.DECODE);
                log.error("[AssetOp] 待办行 payload 解不开，已推迟待人工处理 {}", poisoned.cause());
                yield Optional.empty();
            }
        };
    }

    // ================================================================ 单行

    /**
     * 循环路径处理一行（worker 线程上，阻塞）：等投递（上界 = 投递预算 + 余量）→ 离线读账本 → 落库都在本线程。
     */
    Processed processClaimed(AssetOp op) {
        long nowMs = clockMs.getAsLong();
        Optional<AssetRpc> rpc = AssetOpDecisions.rpcFor(op, nowMs);
        if (rpc.isEmpty()) {
            return badStream(op, nowMs);
        }
        long budget = config.deliveryBudgetMs();
        CompletableFuture<Delivery> delivery = applier.deliver(rpc.get(), op.request(), Deadline.after(budget));
        Delivery d;
        try {
            // 投递自己有界（每次调用 ≤ min(800 ms, 剩余)，重查等待受预算约束）；这里的上限只防实现 bug 把 worker 永远钉住
            d = delivery.get(budget + GuildLimits.ASSET_SETTLE_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            d = Delivery.failed(new AssetDeliveryException("投递没有在预算内结束", e));
        } catch (ExecutionException e) {
            d = Delivery.failed(new AssetDeliveryException("投递异常完成", e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d = Delivery.failed(new AssetDeliveryException("等待投递时被中断", e));
        }
        return conclude(op, DeliveryOrigin.LOOP, rpc.get(), nowMs, d);
    }

    /**
     * 同步路径（请求提交后立刻投一次）：投递全程异步，离线读与落库在落库执行器上跑；执行器满了 → 不落库（行仍 PENDING，带着插行租约，
     * 到期后由循环用同一 seq 重投，scene 只读答复），计 {@code store_errors{settle_rejected}}。
     */
    @Override
    public CompletableFuture<Processed> processOne(AssetOp op, DeliveryOrigin origin, long budgetMillis) {
        long nowMs = clockMs.getAsLong();
        Optional<AssetRpc> rpc = AssetOpDecisions.rpcFor(op, nowMs);
        if (rpc.isEmpty()) {
            return onSettle(op, () -> badStream(op, nowMs));
        }
        long budget = Math.max(0, Math.min(budgetMillis, config.deliveryBudgetMs()));
        // 同步路径的落库必须在请求截止之前做完（基线 settle 在请求的 goroutine 里内联跑，整个 RPC 不超过预算）：
        // 预算 = min(2500, 请求剩余 − 1000)，所以「投递预算 + (1000 − 700)」之内开工的落库能在请求截止前做完、还留回读的时间；
        // 在落库执行器里排队过了这个点才轮到的，不落库（行留 PENDING、带插行租约，到期由循环用同一 seq 重投，scene 只读答复，
        // 以 LOOP 身份终结并推送 9 / 13）——不能让回包拖过 gate 的 5 s，把已提交的预留报成失败。
        long settleStartBy = origin == DeliveryOrigin.SYNC
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget + SYNC_SETTLE_START_SLACK_MS)
                : Long.MAX_VALUE;
        CompletableFuture<Delivery> delivery;
        try {
            delivery = applier.deliver(rpc.get(), op.request(), Deadline.after(budget));
        } catch (RuntimeException e) {
            delivery = CompletableFuture.completedFuture(Delivery.failed(new AssetDeliveryException("投递抛异常", e)));
        }
        return delivery
                .handle((d, error) -> error != null
                        ? Delivery.failed(new AssetDeliveryException("投递异常完成", error))
                        : d)
                .thenCompose(d -> onSettle(op, () -> {
                    if (settleStartBy != Long.MAX_VALUE && System.nanoTime() - settleStartBy > 0) {
                        metrics.storeError(StoreOp.SETTLE_LATE);
                        log.warn("[AssetOp] 同步落库排队太久、已来不及在请求截止前回包，本次不落库（行留在 PENDING，租约到期后由循环用同一 seq"
                                + " 重投并推送） op_id={} stream={} seq={}", Long.toUnsignedString(op.opId()), op.stream(),
                                Long.toUnsignedString(op.seq()));
                        return Processed.pending(null);
                    }
                    return conclude(op, origin, rpc.get(), nowMs, d);
                }));
    }

    /** 同步落库最晚开工点 = 投递预算之后再留的余量：请求尾巴 1000 ms 减去落库自己的 700 ms。 */
    static final long SYNC_SETTLE_START_SLACK_MS = GuildLimits.ASSET_SYNC_TAIL_RESERVE_MS - GuildLimits.ASSET_SETTLE_BUDGET_MS;

    /** 在落库执行器上跑一步阻塞的收尾；被拒绝时折成「没落库」（future 不异常完成）。 */
    private CompletableFuture<Processed> onSettle(AssetOp op, java.util.function.Supplier<Processed> step) {
        CompletableFuture<Processed> out = new CompletableFuture<>();
        try {
            settle.execute(() -> {
                try {
                    out.complete(step.get());
                } catch (Throwable t) {
                    log.error("[AssetOp] 落库步骤出错 op_id={}", Long.toUnsignedString(op.opId()), t);
                    out.complete(Processed.pending(null));
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.storeError(StoreOp.SETTLE_REJECTED);
            log.warn("[AssetOp] 落库执行器已满，本次不落库（行留在 PENDING，租约到期后由循环用同一 seq 重投） op_id={} stream={} seq={}",
                    Long.toUnsignedString(op.opId()), op.stream(), Long.toUnsignedString(op.seq()));
            out.complete(Processed.pending(null));
        }
        return out;
    }

    /** 流号非法 = 坏行：绝不猜方向（猜错就是把扣钱发成发钱），ALERT 重排；这一行从未到达 scene，不覆盖答复列（E12）。 */
    private Processed badStream(AssetOp op, long nowMs) {
        metrics.unknown(op.stream());
        log.error("[AssetOp] 待办行的流号非法，无法决定投递方向 op_id={} stream={} seq={}", Long.toUnsignedString(op.opId()),
                op.stream(), Long.toUnsignedString(op.seq()));
        return rescheduleWithoutAnswer(op, alertNextMs(nowMs), nowMs, AssetOpAction.ALERT, null);
    }

    /** 投递之后的全部收尾（阻塞：离线读账本、终结或重排）。 */
    Processed conclude(AssetOp op, DeliveryOrigin origin, AssetRpc rpc, long nowMs, Delivery delivery) {
        Delivery d = tryPersistedLedger(op, delivery);
        if (d.noAnswer()) {
            // 传输 / 定位故障：结局未知，按退避重投；不覆盖行上的答复列（E12）
            long next = AssetOpDecisions.nextAttemptMs(nowMs, op.attempts(), config.baseBackoffMs(),
                    config.maxBackoff().toMillis(), jitter);
            log.info("[AssetOp] 投递没拿到答复，退避重投 op_id={} stream={} seq={} attempts={}: {}",
                    Long.toUnsignedString(op.opId()), op.stream(), Long.toUnsignedString(op.seq()),
                    Integer.toUnsignedString(op.attempts()), d.error().toString());
            return rescheduleWithoutAnswer(op, next, nowMs, AssetOpAction.RETRY, null);
        }
        AssetOpResult res = d.result();
        return switch (AssetOpDecisions.decide(res, d.error())) {
            case FINALIZE -> finalizeOp(op, origin, rpc, res, nowMs);
            case AWAIT_DURABLE -> reschedule(op, nowMs + GuildLimits.ASSET_AWAIT_DURABLE_DELAY_MS, res, nowMs,
                    AssetOpAction.AWAIT_DURABLE);
            case RETRY -> reschedule(op, AssetOpDecisions.nextAttemptMs(nowMs, op.attempts(), config.baseBackoffMs(),
                    config.maxBackoff().toMillis(), jitter), res, nowMs, AssetOpAction.RETRY);
            case ALERT -> {
                if (d.error() != null && AssetOpDecisions.isOutcomeFlip(d.error())) {
                    // 翻转已由调用方计过 outcome_flip_total，这里只留日志证据
                    log.error("[AssetOp] 结局翻转，不终结 op_id={} stream={} seq={}: {}", Long.toUnsignedString(op.opId()),
                            op.stream(), Long.toUnsignedString(op.seq()), d.error().getMessage());
                } else {
                    metrics.unknown(op.stream());
                    log.error("[AssetOp] scene 回 UNKNOWN，不终结 op_id={} stream={} seq={} epoch={} reason={}",
                            Long.toUnsignedString(op.opId()), op.stream(), Long.toUnsignedString(op.seq()),
                            Long.toUnsignedString(op.streamEpoch()), Integer.toUnsignedString(res.reason()));
                }
                yield reschedule(op, alertNextMs(nowMs), res, nowMs, AssetOpAction.ALERT);
            }
        };
    }

    /**
     * 离线读已落盘账本（tryPersistedLedger，reconcile.go:635-667）：只有本地合成 NOT_HERE、已试过 ≥ min-attempts 次、接了读端时才读。
     * 读到 APPLIED / REJECTED（天然 durable）→ 换成这个结论；其余照旧。
     */
    private Delivery tryPersistedLedger(AssetOp op, Delivery delivery) {
        if (ledger == null || !delivery.answered() || !delivery.result().local()
                || Integer.compareUnsigned(op.attempts(), config.ledgerReadMinAttempts()) < 0) {
            return delivery;
        }
        PersistedLedgerReader.Outcome outcome = ledger.classify(op);
        return switch (outcome) {
            case Absent ignored -> {
                metrics.ledgerRead(LedgerReadResult.ABSENT);
                yield delivery;
            }
            case Unseen ignored -> {
                metrics.ledgerRead(LedgerReadResult.UNSEEN);
                yield delivery;
            }
            case Unreadable unreadable -> {
                metrics.ledgerRead(LedgerReadResult.ERROR);
                metrics.storeError(StoreOp.LEDGER_READ);
                log.error("[AssetOp] 读已落盘账本失败 op_id={}: {}", Long.toUnsignedString(op.opId()), unreadable.reason());
                yield delivery;
            }
            case Finalized finalized -> {
                metrics.ledgerRead(LedgerReadResult.FINALIZED);
                log.info("[AssetOp] 离线读到已落盘结局，可终结 op_id={} stream={} seq={} outcome={} partial={}",
                        Long.toUnsignedString(op.opId()), op.stream(), Long.toUnsignedString(op.seq()),
                        finalized.result().outcome(), finalized.result().partial());
                yield Delivery.answered(finalized.result());
            }
        };
    }

    /**
     * 终结（finalize，reconcile.go:556-583）。状态算不出来时<b>不写库</b>：那说明 Decide 与 FinalStatus 之间有 bug，按坏行告警比按 PENDING 写进
     * status 列安全得多。终结时刻取落库这一刻（终态行的 next_attempt_ms = 终结时刻，回档检查依赖它；取晚不取早）。
     */
    private Processed finalizeOp(AssetOp op, DeliveryOrigin origin, AssetRpc rpc, AssetOpResult res, long nowMs) {
        AssetOpStatus status = AssetOpDecisions.finalStatus(rpc, res, op);
        if (status == AssetOpStatus.PENDING) {
            metrics.unknown(op.stream());
            log.error("[AssetOp] 终结分支算不出最终状态（bug）op_id={} stream={} seq={} outcome={}",
                    Long.toUnsignedString(op.opId()), op.stream(), Long.toUnsignedString(op.seq()), res.outcome());
            return reschedule(op, alertNextMs(nowMs), res, nowMs, AssetOpAction.ALERT);
        }
        FinalizeResult result;
        try {
            result = store.finalizeOp(op.opId(), status, res, clockMs.getAsLong(), origin, settleDeadline());
        } catch (RuntimeException e) {
            metrics.storeError(StoreOp.FINALIZE);
            log.error("[AssetOp] 终结失败 op_id={}（行仍 PENDING，下一轮用同一 seq 再投，scene 只读答复）: {}",
                    Long.toUnsignedString(op.opId()), e.toString());
            return Processed.pending(res);
        }
        if (result.finalized()) {
            metrics.finalized(op.stream(), status);
            if (status == AssetOpStatus.APPLIED_PARTIAL) {
                // 部分发放不做对侧账，必须留下人工补偿线索
                log.error("[AssetOp] 部分发放，已终结但不入对侧账，需人工补偿 op_id={} stream={} seq={} corr={}",
                        Long.toUnsignedString(op.opId()), op.stream(), Long.toUnsignedString(op.seq()),
                        Long.toUnsignedString(op.correlationId()));
            }
            return new Processed(res, true, status);
        }
        return Processed.pending(res);
    }

    /** 重排（带 scene 答复；reschedule，reconcile.go:594-611）。写库前过一道 27007 粘性；返回给调用方的仍是本次真实答复。 */
    private Processed reschedule(AssetOp op, long nextAttemptMs, AssetOpResult res, long nowMs, AssetOpAction reason) {
        RescheduleResult result;
        try {
            result = store.reschedule(op, nextAttemptMs, AssetOpDecisions.carryPartialReason(op, res), nowMs, settleDeadline());
        } catch (RuntimeException e) {
            metrics.storeError(StoreOp.RESCHEDULE);
            log.error("[AssetOp] 重排失败 op_id={}: {}", Long.toUnsignedString(op.opId()), e.toString());
            return Processed.pending(res);
        }
        return afterReschedule(op, result, reason, res);
    }

    /** 重排（没有 scene 答复，E12）：只推进 attempts / next_attempt_ms、清租约。 */
    private Processed rescheduleWithoutAnswer(AssetOp op, long nextAttemptMs, long nowMs, AssetOpAction reason,
                                              AssetOpResult res) {
        RescheduleResult result;
        try {
            result = store.rescheduleWithoutAnswer(op, nextAttemptMs, nowMs, settleDeadline());
        } catch (RuntimeException e) {
            metrics.storeError(StoreOp.RESCHEDULE);
            log.error("[AssetOp] 重排失败 op_id={}: {}", Long.toUnsignedString(op.opId()), e.toString());
            return Processed.pending(res);
        }
        return afterReschedule(op, result, reason, res);
    }

    private Processed afterReschedule(AssetOp op, RescheduleResult result, AssetOpAction reason, AssetOpResult res) {
        if (result == RescheduleResult.LEASE_LOST) {
            // 租约被别的副本接管：本次结果没写进去，但那一行已经有人管，既不是故障也无需重试；只有计数看得见它，所以一定要计
            metrics.rescheduleLost(op.stream());
            log.info("[AssetOp] 租约已被接管，本次重排落空 op_id={} stream={} seq={}", Long.toUnsignedString(op.opId()),
                    op.stream(), Long.toUnsignedString(op.seq()));
        } else {
            metrics.rescheduled(op.stream(), reason);
        }
        return Processed.pending(res);
    }

    private long alertNextMs(long nowMs) {
        return nowMs + config.maxBackoff().toMillis();
    }

    /** 落库一步的独立预算（不继承请求 / 投递的截止，也不随关停取消）。 */
    private static Deadline settleDeadline() {
        return Deadline.after(GuildLimits.ASSET_SETTLE_BUDGET_MS);
    }

    // ================================================================ 最老未决年龄

    /** 刷新「最老未决行年龄」（refreshPendingAge，reconcile.go:669-694）：可观测性，不是正确性路径。 */
    void refreshPendingAge() {
        long nowMs = clockMs.getAsLong();
        for (int stream : AssetOpMetrics.GUILD_STREAMS) {
            OptionalLong created;
            try {
                created = store.oldestPendingCreatedMs(stream, Deadline.after(GuildLimits.ASSET_STORE_READ_BUDGET_MS));
            } catch (RuntimeException e) {
                metrics.storeError(StoreOp.PENDING_AGE);
                log.error("[AssetOp] 读最老未决行失败 stream={}: {}", stream, e.toString());
                continue;
            }
            double age = 0;
            if (created.isPresent() && Long.compareUnsigned(nowMs, created.getAsLong()) > 0) {
                age = (nowMs - created.getAsLong()) / 1000.0;
            }
            metrics.pendingOldestAge(stream, age);
        }
    }
}
