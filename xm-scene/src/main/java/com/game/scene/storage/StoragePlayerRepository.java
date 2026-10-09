package com.game.scene.storage;

import com.game.player.store.OwnerState;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.HandOffMode;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.PlayerState;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.StorageOp;
import com.game.scene.metrics.SceneMetrics.WriteResult;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository;
import com.game.scene.world.PlayerRepository.HandOffAttempts;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * {@link PlayerRepository} 的 MySQL 实现：{@link PlayerStore} 的阻塞调用放在有界的存储线程池上，
 * 加载结果投递回场景逻辑线程。
 *
 * <p>写（最终写回并释放 / 只释放）的失败处理：
 * <ul>
 *   <li>瞬时故障（取不到连接、网络闪断、锁等待 / 语句超时等，{@link #isTransient}）在一个截止时间内按退避 + 抖动重试，
 *       最多 {@link RetryPolicy#maxAttempts()} 次；围栏拒绝（epoch 已被夺走）不是故障，不重试；</li>
 *   <li>重试用尽、非瞬时故障、线程池拒绝、停服时被丢弃：记 ERROR，带 player_id / epoch / 场景配置 / 坐标，供人工修复；
 *       累加 {@link #writeFailures()}（进程内计数，不带 player_id 维度）。</li>
 * </ul>
 * 每个写任务的结局（已释放 / 围栏拒绝 / 失败 / 被拒 / 已交出 …）与耗时恰好记一次指标（{@link SceneMetrics#storageWrite}）；
 * 停服时被 {@code shutdownNow} 丢弃的任务不经过这里，由停服流程逐条记 ERROR（进程随即退出，指标已无人抓取）。
 * 在线存盘（{@link #saveProgress}，周期存盘用）同样带重试，结局投递回逻辑线程；进程被 kill 时丢的是最近一次在线存盘之后的增量，
 * 归属租约过期后玩家可以重新进入，读到的是最近一次落库的状态。
 *
 * <p><b>交出（{@link #handOff}）与探测（{@link #probe}）</b>（scene-handoff-spec §5.2）：
 * <ul>
 *   <li>交出复用上面的重试（瞬时故障、截止时间、次数上限）。每次尝试现取墙钟 {@code now_i}，传
 *       {@code leaseUntil = L_i = now_i + 租约}、{@code requireLeaseAtLeast = now_i + M}（M = {@link HandOffSettings#leaseMargin()}），
 *       并在发出前记下 {@code L_i}。某次尝试回 Fenced 而那一行是 (E+1, 未释放, 更早某次的 L_j)，就是「更早那次已提交、应答丢了」，
 *       改判为已交出——不当成失去归属去踢人；</li>
 *   <li>重试用尽 / 非瞬时故障 / 被拒的交出是「结局不明」（{@link HandOffOutcome.Failed}），不计入 {@link #writeFailures()}，
 *       由调用方接着探测：加锁读，读失败在「第一次尝试起 M − 2 s」之内退避重试（至少读一次）；(E, 未释放) → 没提交；
 *       (E+1, 未释放, ∈ {L_i}) → 已交出；其余或到截止 → 失去（fail-closed）；</li>
 *   <li>停服：结局在逻辑线程上的处理可能再提交「释放 E+1」或探测（冻结中被停服写回移出的实例，交出 / 探测认出已提交就释放 E+1），
 *       这些必须赶在存储线程池关闭之前提交，否则被已关闭的池拒绝、E+1 悬空到租约过期。所以停服流程在写回交出去之后、关池之前
 *       等全部已提交的交出 / 探测「结局已在逻辑线程上处理完」（{@link #awaitTransfersSettled}，与写回共用停服预算）；</li>
 *   <li>结局投递被拒（逻辑线程已停）而结局是「已交出」：交出通知只由逻辑线程发出，它收不到结局就一定没发，E+1 没有别的知情者，
 *       存储线程直接带围栏释放 E+1（逻辑线程先于存储线程池停下时的兜底；正常停服顺序下走上一条）。</li>
 * </ul>
 *
 * <p>{@link PlayerRow} 是可变 JavaBean，只在存储线程内使用；跨线程传递的都是不可变的 {@link PlayerData} /
 * {@link PlayerSave} / 结局记录。
 */
public final class StoragePlayerRepository implements PlayerRepository {

    private static final Logger log = LoggerFactory.getLogger(StoragePlayerRepository.class);

    /** 探测读失败后退避的上限：探测窗口只有几秒，不让翻倍的退避把最后一次机会也等掉。 */
    static final long PROBE_MAX_BACKOFF_MS = 1_000;

    /**
     * 写的重试策略。
     *
     * @param maxAttempts  最多尝试次数（含第一次）
     * @param firstBackoff 第一次重试前的等待，之后翻倍
     * @param deadline     从第一次尝试起的总时间预算：剩余时间不够再等一次就放弃
     */
    public record RetryPolicy(int maxAttempts, Duration firstBackoff, Duration deadline) {

        public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5));

        public RetryPolicy {
            if (maxAttempts < 1 || firstBackoff.isNegative() || deadline.isNegative()) {
                throw new IllegalArgumentException("重试策略非法");
            }
        }
    }

    /** 可中断的等待（测试替身不真等）。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final PlayerStore store;
    private final ExecutorService storageExecutor;
    private final Executor logicExecutor;
    private final RetryPolicy retry;
    private final HandOffSettings handOff;
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;
    private final LongSupplier nanoClock;
    private final LongSupplier wallClockMs;
    private final SceneMetrics metrics;
    private final AtomicLong writeFailures = new AtomicLong();
    /**
     * 已提交、结局还没在逻辑线程上处理完的交出 / 探测数：提交时加一，结局回调在逻辑线程上执行完（或投递被拒、兜底做完）后减一。
     * 交出结局不明时回调里接着提交探测，探测先加一、交出后减一，中途不会短暂归零。
     */
    private final AtomicInteger transfersUnsettled = new AtomicInteger();
    /** {@link #awaitTransfersSettled} 等待归零用的监视器（逻辑线程上只在归零那一刻短暂持有，用于唤醒）。 */
    private final Object settledMonitor = new Object();

    public StoragePlayerRepository(PlayerStore store, ExecutorService storageExecutor, Executor logicExecutor,
                                   SceneMetrics metrics) {
        this(store, storageExecutor, logicExecutor, metrics, HandOffSettings.DEFAULT);
    }

    public StoragePlayerRepository(PlayerStore store, ExecutorService storageExecutor, Executor logicExecutor,
                                   SceneMetrics metrics, HandOffSettings handOff) {
        this(store, storageExecutor, logicExecutor, RetryPolicy.DEFAULT, handOff, Thread::sleep,
                backoff -> ThreadLocalRandom.current().nextLong(backoff / 2 + 1), System::nanoTime,
                System::currentTimeMillis, metrics);
    }

    /** 交出参数取缺省、墙钟取系统时钟。 */
    public StoragePlayerRepository(PlayerStore store, ExecutorService storageExecutor, Executor logicExecutor,
                                   RetryPolicy retry, Sleeper sleeper, LongUnaryOperator jitter, LongSupplier nanoClock,
                                   SceneMetrics metrics) {
        this(store, storageExecutor, logicExecutor, retry, HandOffSettings.DEFAULT, sleeper, jitter, nanoClock,
                System::currentTimeMillis, metrics);
    }

    /**
     * @param jitter      给定本次退避毫秒数，返回额外加上的随机毫秒数（避免多个存储线程同时重试）
     * @param nanoClock   单调时钟，用于重试截止时间、探测截止时间与写耗时指标
     * @param wallClockMs 墙钟（Unix 毫秒），用于交出的新租约值与剩余租约下限（与续约、夺权同一种时钟）
     */
    public StoragePlayerRepository(PlayerStore store, ExecutorService storageExecutor, Executor logicExecutor,
                                   RetryPolicy retry, HandOffSettings handOff, Sleeper sleeper, LongUnaryOperator jitter,
                                   LongSupplier nanoClock, LongSupplier wallClockMs, SceneMetrics metrics) {
        this.store = store;
        this.storageExecutor = storageExecutor;
        this.logicExecutor = logicExecutor;
        this.retry = retry;
        this.handOff = handOff;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.nanoClock = nanoClock;
        this.wallClockMs = wallClockMs;
        this.metrics = metrics;
    }

    /** 最终失败（丢失）的写回 / 释放次数：重试用尽、非瞬时故障、线程池拒绝（在线存盘不算：它下个周期重写；交出不算：结局交给探测）。停服时被丢弃的由调用方另计。 */
    public long writeFailures() {
        return writeFailures.get();
    }

    @Override
    public void load(long playerId, Consumer<LoadResult> onLoaded) {
        try {
            storageExecutor.execute(() -> deliver(playerId, onLoaded, loadNow(playerId)));
        } catch (RejectedExecutionException e) {
            // 存储线程池已满或已关闭：按加载失败处理（进场失败，fail-closed）。仍然异步回调，保持接口契约。
            log.error("存储线程池拒绝加载任务 player={}", playerId);
            deliver(playerId, onLoaded, new LoadResult.Failed(e));
        }
    }

    private LoadResult loadNow(long playerId) {
        try {
            Optional<PlayerRow> row = store.findPlayer(playerId);
            if (row.isEmpty()) {
                return new LoadResult.NotFound();
            }
            return new LoadResult.Found(toData(row.get(), store.loadState(playerId)));
        } catch (RuntimeException e) {
            return new LoadResult.Failed(e);
        }
    }

    private void deliver(long playerId, Consumer<LoadResult> onLoaded, LoadResult result) {
        try {
            logicExecutor.execute(() -> onLoaded.accept(result));
        } catch (RejectedExecutionException e) {
            log.warn("场景逻辑线程已停止，丢弃加载结果 player={}", playerId);
        }
    }

    @Override
    public void save(PlayerSave save) {
        submit(new WriteTask(Kind.SAVE, save, save.playerId(), save.ownerEpoch(), null));
    }

    @Override
    public void release(long playerId, long ownerEpoch) {
        submit(new WriteTask(Kind.RELEASE, null, playerId, ownerEpoch, null));
    }

    @Override
    public void saveProgress(PlayerSave save, Consumer<ProgressResult> onDone) {
        submit(new WriteTask(Kind.PROGRESS, save, save.playerId(), save.ownerEpoch(), onDone));
    }

    @Override
    public void handOff(PlayerSave frozen, HandOffMode mode, Consumer<HandOffOutcome> onDone) {
        Objects.requireNonNull(mode, "mode");
        transfersUnsettled.incrementAndGet();
        HandOffTask task = new HandOffTask(frozen, mode, onDone);
        if (mode == HandOffMode.RELEASE) {
            // 批次 5.4 先行件的占位：「交出并释放」的重试改判与探测要按另一张表判（已提交的那次读回来是「已释放」），
            // 那部分还没接上。照现有的 HOLD 逻辑去做会把已提交的传送误判成围栏，所以这里一笔库事务都不发，
            // 以「一次也没尝试」的结局不明交回——探测见到空的尝试记录直接判没提交，调用方原地解冻。
            metrics.storageWrite(StorageOp.HANDOFF, WriteResult.REJECTED, 0);
            log.error("交出并释放（跨 zone 传送，批次 5.4）的存储实现还没接上（施工中），不发库事务、按没提交交回 {}", task.describe());
            task.deliver(task.failed());
            return;
        }
        submit(task);
    }

    @Override
    public void probe(HandOffOutcome.Failed failed, Consumer<ProbeOutcome> onDone) {
        transfersUnsettled.incrementAndGet();
        if (failed.attempts().leases().isEmpty()) {
            // 一次也没尝试（交出任务被线程池拒绝）：一定没提交，不必读库，也不依赖此刻已满的存储线程池。
            metrics.storageWrite(StorageOp.PROBE, WriteResult.NOT_COMMITTED, 0);
            new ProbeTask(failed, onDone).deliver(new ProbeOutcome.NotCommitted());
            return;
        }
        submit(new ProbeTask(failed, onDone));
    }

    /**
     * 存储线程池排队数低于「线程数 × 2」才接在线存盘：在线存盘可以晚一个周期，续约、最终写回、加载不能等——
     * 库变慢时不让周期存盘把它们堵在同一个 FIFO 队列后面（续约晚于租约会让别的会话强制夺权，最终写回随之被围栏拒掉）。
     */
    @Override
    public boolean acceptsProgress() {
        if (storageExecutor instanceof ThreadPoolExecutor pool) {
            return pool.getQueue().size() < Math.max(2, pool.getCorePoolSize() * 2);
        }
        return true;
    }

    /**
     * 停服用（在关存储线程池<b>之前</b>、写回交出去之后调用；阻塞调用线程，不能在逻辑线程上调用）：等全部已提交的交出 / 探测
     * 「结局已在逻辑线程上处理完」——处理里提交的「释放 E+1」/ 探测随之都已交给仍开着的存储线程池，关池后照常落库，
     * 不会被已关闭的池拒掉（scene-handoff-spec §5.2、§5.5 停服行）。交出带重试截止、探测带截止，等待有界。
     * 等待用真实单调时钟（与注入的 {@code nanoClock} 无关）。
     *
     * @param timeoutNanos 最多等多久
     * @return 仍未处理完的交出 / 探测数（0 = 已全部处理完）
     */
    public int awaitTransfersSettled(long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (settledMonitor) {
            int unsettled;
            while ((unsettled = transfersUnsettled.get()) > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return unsettled;
                }
                TimeUnit.NANOSECONDS.timedWait(settledMonitor, left);
            }
            return 0;
        }
    }

    /** 一个交出 / 探测的结局已处理完（逻辑线程上回调执行完，或投递被拒后的兜底做完）。 */
    private void transferSettled() {
        if (transfersUnsettled.decrementAndGet() == 0) {
            synchronized (settledMonitor) {
                settledMonitor.notifyAll();
            }
        }
    }

    private void submit(StorageTask task) {
        try {
            storageExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            task.rejected();
        }
    }

    /**
     * 停服时 {@link ExecutorService#shutdownNow()} 丢掉的任务若是本仓库的写任务，返回它的描述（用于逐条记 ERROR）。
     */
    public static Optional<String> describeDropped(Runnable task) {
        return task instanceof StorageTask storage ? Optional.of(storage.describe()) : Optional.empty();
    }

    /**
     * 在存储线程上按 {@link RetryPolicy} 执行 {@code attempt}：瞬时故障在截止时间内退避重试。
     *
     * @return {@code attempt} 的结果；放弃（重试用尽、非瞬时故障、重试等待被中断）时返回 null，原因已交给 {@link StorageTask#givenUp}
     */
    private <T> T withRetry(StorageTask task, Supplier<T> attempt) {
        long deadline = nanoClock.getAsLong() + retry.deadline().toNanos();
        long backoffMs = retry.firstBackoff().toMillis();
        for (int n = 1; ; n++) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                boolean transientError = isTransient(e);
                long waitMs = backoffMs + Math.max(0, jitter.applyAsLong(backoffMs));
                long remainingNanos = deadline - nanoClock.getAsLong();
                if (!transientError || n >= retry.maxAttempts()
                        || remainingNanos <= TimeUnit.MILLISECONDS.toNanos(waitMs)) {
                    task.givenUp("失败 尝试=" + n + " 瞬时故障=" + transientError, e);
                    return null;
                }
                log.warn("{}遇到瞬时故障，{}ms 后重试 尝试={}: {}", task.describe(), waitMs, n, e.toString());
                try {
                    sleeper.sleep(waitMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    task.givenUp("重试等待被中断（停服）", null);
                    return null;
                }
                backoffMs = Math.min(backoffMs * 2, retry.deadline().toMillis());
            }
        }
    }

    /**
     * 可恢复的瞬时故障：取不到连接、连接断开、锁等待 / 查询超时、事务时限到期等。约束冲突、SQL 错误等不是。
     * 这些失败绝大多数发生在提交之前（事务已回滚），重试安全；提交中途断开也会落在这里（结局不明）：写回 / 在线存盘重放同一份
     * 带围栏的写无害，交出靠每次尝试的租约值认领自己的提交、探测兜底。
     */
    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // CannotCreateTransactionException：@Transactional 方法开事务时取不到连接（连接池超时、库拒绝新连接），
            // 事务还没开始、什么也没写，重试安全；它不是 DataAccessException，要单独认。
            // TransactionTimedOutException：带时限的事务（交出 / 探测）在下一条语句前发现已到期，抛出即回滚，重试安全。
            if (t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof TransactionTimedOutException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    /** 跑在存储线程池上的任务。带自描述，停服丢弃时可逐条记录。 */
    private abstract static sealed class StorageTask implements Runnable permits WriteTask, HandOffTask, ProbeTask {

        abstract String describe();

        /** 存储线程池拒绝了这个任务（积压满或已关闭）：在提交方线程上记指标、日志并按约定回调。 */
        abstract void rejected();

        /** {@link #withRetry} 放弃时记录原因。 */
        void givenUp(String why, Throwable error) {
            log.warn("{}{}", describe(), why, error);
        }
    }

    /** 写任务的种类。 */
    private enum Kind {
        /** 最终写回并释放归属。 */
        SAVE,
        /** 只释放归属。 */
        RELEASE,
        /** 在线存盘（不释放），有结果回调。 */
        PROGRESS
    }

    /** 一个写任务（写回 / 释放 / 在线存盘）。 */
    private final class WriteTask extends StorageTask {

        private final Kind kind;
        private final PlayerSave save;
        private final long playerId;
        private final long ownerEpoch;
        private final Consumer<ProgressResult> onDone;

        WriteTask(Kind kind, PlayerSave save, long playerId, long ownerEpoch, Consumer<ProgressResult> onDone) {
            this.kind = kind;
            this.save = save;
            this.playerId = playerId;
            this.ownerEpoch = ownerEpoch;
            this.onDone = onDone;
        }

        @Override
        public void run() {
            long started = nanoClock.getAsLong();
            WriteResult result = runWithRetry();
            metrics.storageWrite(op(), result, nanoClock.getAsLong() - started);
            complete(result);
        }

        /** 执行（含重试），返回结局；失败已记 ERROR 并计入 {@link #writeFailures()}。 */
        private WriteResult runWithRetry() {
            Boolean applied = withRetry(this, this::apply);
            if (applied == null) {
                return WriteResult.FAILED;
            }
            if (applied) {
                log.debug("{}完成", describe());
                return kind == Kind.PROGRESS ? WriteResult.SAVED : WriteResult.RELEASED;
            }
            log.warn("{}被归属围栏拒绝（epoch 已被新的进场取代、已释放或玩家已不存在），丢弃", describe());
            return WriteResult.FENCED;
        }

        StorageOp op() {
            return switch (kind) {
                case SAVE -> StorageOp.SAVE;
                case RELEASE -> StorageOp.RELEASE;
                case PROGRESS -> StorageOp.PROGRESS;
            };
        }

        boolean apply() {
            return switch (kind) {
                case SAVE -> store.saveStateAndRelease(toRow(save), save.state());
                case RELEASE -> store.releaseOwnership(playerId, ownerEpoch);
                case PROGRESS -> store.saveStateHeld(toRow(save), save.state());
            };
        }

        @Override
        void rejected() {
            metrics.storageWrite(op(), WriteResult.REJECTED, 0);
            if (kind == Kind.PROGRESS) {
                log.warn("存储线程池拒绝在线存盘，下个周期重写 {}", describe());
            } else if (kind == Kind.RELEASE) {
                // 只释放丢失：没有数据丢失，归属等租约过期后自动可用，不需要人工修复（仍计一次写丢失）
                writeFailures.incrementAndGet();
                log.error("存储线程池拒绝释放任务，本次释放丢失，归属等租约过期（至多 {}）后自动可用 {}", PlayerStore.OWNER_LEASE,
                        describe());
            } else {
                // 线程池积压上万或已关闭。写回丢失：玩家数据回到上次落库的状态。
                writeFailures.incrementAndGet();
                log.error("存储线程池拒绝写任务，本次写丢失（需人工修复） {}", describe());
            }
            complete(WriteResult.REJECTED);
        }

        /** 写最终失败：在线存盘只告警（下个周期按最新状态重写）；写回 / 释放记 ERROR 并计入 {@link #writeFailures()}。 */
        @Override
        void givenUp(String why, Throwable error) {
            if (kind == Kind.PROGRESS) {
                log.warn("{}{}，下个周期重写", describe(), why, error);
                return;
            }
            writeFailures.incrementAndGet();
            log.error("{}{}，放弃（需人工修复）", describe(), why, error);
        }

        /** 在线存盘把结局投递回逻辑线程（恰好一次）；其余写没有回调。 */
        void complete(WriteResult result) {
            if (onDone == null) {
                return;
            }
            ProgressResult progress = switch (result) {
                case SAVED, RELEASED -> ProgressResult.SAVED;
                case FENCED -> ProgressResult.FENCED;
                default -> ProgressResult.FAILED;
            };
            try {
                logicExecutor.execute(() -> onDone.accept(progress));
            } catch (RejectedExecutionException e) {
                log.warn("场景逻辑线程已停止，丢弃在线存盘结果 player={}", Long.toUnsignedString(playerId));
            }
        }

        @Override
        String describe() {
            if (save == null) {
                return "释放归属 player=" + Long.toUnsignedString(playerId) + " epoch=" + ownerEpoch;
            }
            return (kind == Kind.PROGRESS ? "在线存盘" : "玩家写回") + describeSave(save);
        }
    }

    /**
     * 交出任务。{@link #leases} 只在执行它的那一个存储线程上读写（重试在同一线程上串行）。
     */
    private final class HandOffTask extends StorageTask {

        private final PlayerSave frozen;
        /** 这次交出的模式（随结局不明的记录带给探测）。先行件阶段只有 HOLD 会真的执行。 */
        private final HandOffMode mode;
        private final Consumer<HandOffOutcome> onDone;
        /** 每次尝试写下的新租约值 L_i，按尝试先后；在发出之前记下，应答丢了也认得出。 */
        private final List<Long> leases = new ArrayList<>();
        private long firstAttemptNanos;

        HandOffTask(PlayerSave frozen, HandOffMode mode, Consumer<HandOffOutcome> onDone) {
            this.frozen = frozen;
            this.mode = mode;
            this.onDone = onDone;
        }

        @Override
        public void run() {
            long started = nanoClock.getAsLong();
            firstAttemptNanos = started;
            HandOffResult result = withRetry(this, this::attempt);
            HandOffOutcome outcome = result == null ? failed() : classify(result);
            metrics.storageWrite(StorageOp.HANDOFF, resultOf(outcome), nanoClock.getAsLong() - started);
            deliver(outcome);
        }

        private HandOffResult attempt() {
            long now = wallClockMs.getAsLong();
            long leaseUntil = now + PlayerStore.OWNER_LEASE.toMillis();
            leases.add(leaseUntil);
            return store.handOffOwnership(toRow(frozen), frozen.state(), leaseUntil, now + handOff.leaseMargin().toMillis(),
                    handOff.statementTimeout());
        }

        private HandOffOutcome classify(HandOffResult result) {
            return switch (result) {
                case HandOffResult.HandedOff handedOff -> {
                    log.info("{}已交出 → epoch={}", describe(), handedOff.newEpoch());
                    yield new HandOffOutcome.HandedOff(handedOff.newEpoch());
                }
                case HandOffResult.LeaseTooShort tooShort -> {
                    log.warn("{}没提交：剩余租约不足安全边际 {}（续约近期在失败） 库里={}", describe(), handOff.leaseMargin(),
                            tooShort.owner());
                    yield new HandOffOutcome.LeaseTooShort();
                }
                case HandOffResult.Fenced fenced -> {
                    if (isEarlierAttempt(fenced.owner())) {
                        log.warn("{}重试读到更早一次尝试已提交（租约值 {} 是它写下的），改判为已交出", describe(),
                                fenced.owner().leaseUntil());
                        yield new HandOffOutcome.HandedOff(fenced.owner().ownerEpoch());
                    }
                    log.warn("{}被归属围栏拒绝（已失去归属） 库里={}", describe(), fenced.owner());
                    yield new HandOffOutcome.Fenced();
                }
            };
        }

        /** 库里是 (E+1, 未释放, 更早某次尝试写下的租约值)：那次尝试已提交，只是应答丢了。本次尝试影响 0 行，不算。 */
        private boolean isEarlierAttempt(OwnerState owner) {
            return owner != null && owner.ownerEpoch() == frozen.ownerEpoch() + 1 && !owner.released()
                    && leases.subList(0, leases.size() - 1).contains(owner.leaseUntil());
        }

        private HandOffOutcome.Failed failed() {
            return new HandOffOutcome.Failed(frozen.playerId(), frozen.ownerEpoch(),
                    new HandOffAttempts(firstAttemptNanos, leases), mode);
        }

        @Override
        void rejected() {
            // 一次也没执行：一定没提交。仍按「结局不明」交回（探测见到空的尝试记录直接判没提交），让调用方只有一条失败路径。
            metrics.storageWrite(StorageOp.HANDOFF, WriteResult.REJECTED, 0);
            log.warn("存储线程池拒绝交出任务（一定没提交） {}", describe());
            deliver(failed());
        }

        @Override
        void givenUp(String why, Throwable error) {
            log.warn("{}{}，结局不明，交给探测 已尝试租约={}", describe(), why, leases, error);
        }

        /** 投递结局（恰好一次）；回调在逻辑线程上执行完（或投递被拒、兜底做完）才算这次交出处理完（{@link #awaitTransfersSettled}）。 */
        void deliver(HandOffOutcome outcome) {
            try {
                logicExecutor.execute(() -> {
                    try {
                        onDone.accept(outcome);
                    } finally {
                        transferSettled();
                    }
                });
            } catch (RejectedExecutionException e) {
                try {
                    if (outcome instanceof HandOffOutcome.HandedOff handedOff) {
                        releaseUndelivered(frozen.playerId(), handedOff.newEpoch());
                    } else {
                        log.warn("场景逻辑线程已停止，丢弃交出结局 {} 结局={}", describe(), outcome);
                    }
                } finally {
                    transferSettled();
                }
            }
        }

        @Override
        String describe() {
            return "交出归属" + describeSave(frozen);
        }
    }

    /** 探测任务（只读，加锁读 + 截止时间内重试）。 */
    private final class ProbeTask extends StorageTask {

        private final HandOffOutcome.Failed failed;
        private final Consumer<ProbeOutcome> onDone;

        ProbeTask(HandOffOutcome.Failed failed, Consumer<ProbeOutcome> onDone) {
            this.failed = failed;
            this.onDone = onDone;
        }

        @Override
        public void run() {
            long started = nanoClock.getAsLong();
            ProbeOutcome outcome = probeUntilDeadline();
            metrics.storageWrite(StorageOp.PROBE, resultOf(outcome), nanoClock.getAsLong() - started);
            deliver(outcome);
        }

        /** 至少读一次；读失败 / 锁等待超时在截止前退避重试（不限次数，每次受语句时限约束）。 */
        private ProbeOutcome probeUntilDeadline() {
            long deadline = handOff.probeDeadlineNanos(failed.attempts().firstAttemptNanos());
            long backoffMs = retry.firstBackoff().toMillis();
            for (int n = 1; ; n++) {
                try {
                    return classify(store.probeOwnership(failed.playerId(), handOff.statementTimeout()));
                } catch (RuntimeException e) {
                    long waitMs = backoffMs + Math.max(0, jitter.applyAsLong(backoffMs));
                    if (deadline - nanoClock.getAsLong() <= TimeUnit.MILLISECONDS.toNanos(waitMs)) {
                        log.error("{}到截止仍读不到结论，按失去归属处理（若其实已提交：新 epoch 无人持有，等租约过期；数据是冻结快照） 尝试={}",
                                describe(), n, e);
                        return new ProbeOutcome.Lost();
                    }
                    log.warn("{}读失败，{}ms 后重试 尝试={}: {}", describe(), waitMs, n, e.toString());
                    try {
                        sleeper.sleep(waitMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        log.error("{}重试等待被中断（停服），按失去归属处理", describe());
                        return new ProbeOutcome.Lost();
                    }
                    backoffMs = Math.min(backoffMs * 2, PROBE_MAX_BACKOFF_MS);
                }
            }
        }

        private ProbeOutcome classify(Optional<OwnerState> read) {
            long epoch = failed.fromEpoch();
            if (read.isPresent()) {
                OwnerState owner = read.get();
                if (owner.ownerEpoch() == epoch && !owner.released()) {
                    log.info("{}结论：没提交（仍由 epoch={} 持有）", describe(), epoch);
                    return new ProbeOutcome.NotCommitted();
                }
                if (owner.ownerEpoch() == epoch + 1 && !owner.released()
                        && failed.attempts().leases().contains(owner.leaseUntil())) {
                    log.info("{}结论：其实已提交 → epoch={}", describe(), owner.ownerEpoch());
                    return new ProbeOutcome.HandedOff(owner.ownerEpoch());
                }
            }
            log.error("{}结论：已不是自己的归属，按失去处理 库里={}", describe(), read.orElse(null));
            return new ProbeOutcome.Lost();
        }

        @Override
        void rejected() {
            metrics.storageWrite(StorageOp.PROBE, WriteResult.REJECTED, 0);
            log.error("存储线程池拒绝探测任务，按失去归属处理 {}", describe());
            deliver(new ProbeOutcome.Lost());
        }

        /** 投递结局（恰好一次）；同 {@link HandOffTask#deliver}，回调执行完才算这次探测处理完。 */
        void deliver(ProbeOutcome outcome) {
            try {
                logicExecutor.execute(() -> {
                    try {
                        onDone.accept(outcome);
                    } finally {
                        transferSettled();
                    }
                });
            } catch (RejectedExecutionException e) {
                try {
                    if (outcome instanceof ProbeOutcome.HandedOff handedOff) {
                        releaseUndelivered(failed.playerId(), handedOff.newEpoch());
                    } else {
                        log.warn("场景逻辑线程已停止，丢弃探测结局 {} 结局={}", describe(), outcome);
                    }
                } finally {
                    transferSettled();
                }
            }
        }

        @Override
        String describe() {
            return "交出探测 player=" + Long.toUnsignedString(failed.playerId()) + " epoch=" + failed.fromEpoch()
                    + " 已尝试租约=" + failed.attempts().leases();
        }
    }

    /**
     * 「已交出」的结局投递不到逻辑线程（已停）：交出通知只由逻辑线程发出，所以一定没发，E+1 没有别的知情者。
     * 在当前存储线程上带围栏释放 E+1（同 {@link #release}：重试、指标、失败记 ERROR），让玩家立即可以重新进入而不是等租约过期。
     */
    private void releaseUndelivered(long playerId, long newEpoch) {
        log.warn("场景逻辑线程已停止，交出结局投递不到（交出通知一定没发），在存储线程上释放新 epoch player={} epoch={}",
                Long.toUnsignedString(playerId), newEpoch);
        new WriteTask(Kind.RELEASE, null, playerId, newEpoch, null).run();
    }

    private static WriteResult resultOf(HandOffOutcome outcome) {
        return switch (outcome) {
            case HandOffOutcome.HandedOff handedOff -> WriteResult.HANDED_OFF;
            case HandOffOutcome.LeaseTooShort tooShort -> WriteResult.LEASE_TOO_SHORT;
            case HandOffOutcome.Fenced fenced -> WriteResult.FENCED;
            case HandOffOutcome.Superseded superseded -> WriteResult.SUPERSEDED;
            case HandOffOutcome.Failed failed -> WriteResult.FAILED;
        };
    }

    private static WriteResult resultOf(ProbeOutcome outcome) {
        return switch (outcome) {
            case ProbeOutcome.HandedOff handedOff -> WriteResult.HANDED_OFF;
            case ProbeOutcome.NotCommitted notCommitted -> WriteResult.NOT_COMMITTED;
            case ProbeOutcome.Superseded superseded -> WriteResult.SUPERSEDED;
            case ProbeOutcome.Lost lost -> WriteResult.LOST;
        };
    }

    private static String describeSave(PlayerSave save) {
        Vec3 p = save.position();
        return " player=" + Long.toUnsignedString(save.playerId()) + " epoch=" + save.ownerEpoch()
                + " level=" + save.level() + " scene_config=" + save.sceneConfigId()
                + " pos=(" + p.x() + "," + p.y() + "," + p.z() + ")";
    }

    static PlayerData toData(PlayerRow row, PlayerState state) {
        // 等级列是 INT UNSIGNED：超出 int 的值（只可能来自手工改库）饱和到 int 上限，进场时由 PlayerLevels 压回等级上限（同基线）
        int level = (int) Math.min(row.getLevel(), Integer.MAX_VALUE);
        return new PlayerData(row.getPlayerId(), row.getOwnerEpoch(), row.getClassId(), row.getGender(),
                row.getAppearanceId(), level, row.getSceneConfigId(),
                new Vec3(row.getPosX(), row.getPosY(), row.getPosZ()), state, row.getName());
    }

    /**
     * 写回行。坐标含 NaN / ±Inf 时改写成 (0,0,0) 并记 ERROR：JDBC 驱动拒绝非有限的 double
     * （Connector/J 默认 {@code allowNanAndInf=false}，按非瞬时故障处理），整条写回会失败——等级、场景、位置都丢，
     * 归属也放不掉、要等租约过期。(0,0,0) 是「没有有效坐标」，下次进场落到出生点；其余字段照常写回并释放。
     * 场景逻辑保证坐标有限，走到这里说明上游漏了校验，所以记 ERROR 而不是静默改写。
     */
    static PlayerRow toRow(PlayerSave save) {
        Vec3 position = save.position();
        if (!position.isFinite()) {
            log.error("写回坐标含非有限值，改写为 (0,0,0) 后照常写回（上游校验遗漏） player={} epoch={} scene_config={} pos={}",
                    Long.toUnsignedString(save.playerId()), save.ownerEpoch(), save.sceneConfigId(), position);
            position = Vec3.ORIGIN;
        }
        PlayerRow row = new PlayerRow();
        row.setPlayerId(save.playerId());
        row.setOwnerEpoch(save.ownerEpoch());
        row.setLevel(save.level());
        row.setSceneConfigId(save.sceneConfigId());
        row.setPosX(position.x());
        row.setPosY(position.y());
        row.setPosZ(position.z());
        return row;
    }
}
