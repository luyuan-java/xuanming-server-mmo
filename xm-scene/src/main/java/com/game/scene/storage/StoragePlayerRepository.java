package com.game.scene.storage;

import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * {@link PlayerRepository} 的 MySQL 实现：{@link PlayerStore} 的阻塞调用放在有界的存储线程池上，
 * 加载结果投递回场景逻辑线程。
 *
 * <p>写（最终写回并释放 / 只释放）的失败处理：
 * <ul>
 *   <li>瞬时故障（取不到连接、网络闪断、锁等待超时等，{@link #isTransient}）在一个截止时间内按退避 + 抖动重试，
 *       最多 {@link RetryPolicy#maxAttempts()} 次；围栏拒绝（epoch 已被夺走）不是故障，不重试；</li>
 *   <li>重试用尽、非瞬时故障、线程池拒绝、停服时被丢弃：记 ERROR，带 player_id / epoch / 场景配置 / 坐标，供人工修复；
 *       累加 {@link #writeFailures()}（进程内计数，不带 player_id 维度）。</li>
 * </ul>
 * 权衡（首批）：只在离场时写回、没有周期存盘，进程被 kill 时本次在线期间的增量（换图后的地图与坐标）会丢；
 * 归属租约过期后玩家可以重新进入，读到的是上次离场时的存档。
 *
 * <p>{@link PlayerRow} 是可变 JavaBean，只在存储线程内使用；跨线程传递的都是不可变的 {@link PlayerData} /
 * {@link PlayerSave}。
 */
public final class StoragePlayerRepository implements PlayerRepository {

    private static final Logger log = LoggerFactory.getLogger(StoragePlayerRepository.class);

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
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;
    private final LongSupplier nanoClock;
    private final AtomicLong writeFailures = new AtomicLong();

    public StoragePlayerRepository(PlayerStore store, ExecutorService storageExecutor, Executor logicExecutor) {
        this(store, storageExecutor, logicExecutor, RetryPolicy.DEFAULT, Thread::sleep,
                backoff -> ThreadLocalRandom.current().nextLong(backoff / 2 + 1), System::nanoTime);
    }

    /**
     * @param jitter    给定本次退避毫秒数，返回额外加上的随机毫秒数（避免多个存储线程同时重试）
     * @param nanoClock 单调时钟，只用于重试截止时间
     */
    public StoragePlayerRepository(PlayerStore store, ExecutorService storageExecutor, Executor logicExecutor,
                                   RetryPolicy retry, Sleeper sleeper, LongUnaryOperator jitter, LongSupplier nanoClock) {
        this.store = store;
        this.storageExecutor = storageExecutor;
        this.logicExecutor = logicExecutor;
        this.retry = retry;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.nanoClock = nanoClock;
    }

    /** 最终失败（丢失）的写次数：重试用尽、非瞬时故障、线程池拒绝。停服时被丢弃的由调用方另计。 */
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
            return row.<LoadResult>map(r -> new LoadResult.Found(toData(r))).orElseGet(LoadResult.NotFound::new);
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
        submit(new WriteTask(save, save.playerId(), save.ownerEpoch()));
    }

    @Override
    public void release(long playerId, long ownerEpoch) {
        submit(new WriteTask(null, playerId, ownerEpoch));
    }

    private void submit(WriteTask task) {
        try {
            storageExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            // 线程池积压上万或已关闭。写回丢失：玩家数据回到上次落库的状态；只释放丢失：归属等租约过期。
            writeFailures.incrementAndGet();
            log.error("存储线程池拒绝写任务，本次写丢失（需人工修复） {}", task.describe());
        }
    }

    /**
     * 停服时 {@link ExecutorService#shutdownNow()} 丢掉的任务若是本仓库的写任务，返回它的描述（用于逐条记 ERROR）。
     */
    public static Optional<String> describeDropped(Runnable task) {
        return task instanceof WriteTask write ? Optional.of(write.describe()) : Optional.empty();
    }

    private void runWithRetry(WriteTask task) {
        long deadline = nanoClock.getAsLong() + retry.deadline().toNanos();
        long backoffMs = retry.firstBackoff().toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                if (task.apply()) {
                    log.debug("{}完成", task.describe());
                } else {
                    log.warn("{}被归属围栏拒绝（epoch 已被新的进场取代、已释放或玩家已不存在），丢弃", task.describe());
                }
                return;
            } catch (RuntimeException e) {
                boolean transientError = isTransient(e);
                long waitMs = backoffMs + Math.max(0, jitter.applyAsLong(backoffMs));
                long remainingNanos = deadline - nanoClock.getAsLong();
                if (!transientError || attempt >= retry.maxAttempts()
                        || remainingNanos <= TimeUnit.MILLISECONDS.toNanos(waitMs)) {
                    writeFailures.incrementAndGet();
                    log.error("{}失败，放弃（需人工修复） 尝试={} 瞬时故障={}", task.describe(), attempt, transientError, e);
                    return;
                }
                log.warn("{}遇到瞬时故障，{}ms 后重试 尝试={}: {}", task.describe(), waitMs, attempt, e.toString());
                try {
                    sleeper.sleep(waitMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    writeFailures.incrementAndGet();
                    log.error("{}重试等待被中断（停服），放弃（需人工修复）", task.describe());
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, retry.deadline().toMillis());
            }
        }
    }

    /** 可恢复的瞬时故障：取不到连接、连接断开、锁等待 / 查询超时等。约束冲突、SQL 错误等不是。 */
    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    /** 一个写任务：{@code save != null} 为最终写回并释放，否则只释放。带自描述，停服丢弃时可逐条记录。 */
    private final class WriteTask implements Runnable {

        private final PlayerSave save;
        private final long playerId;
        private final long ownerEpoch;

        WriteTask(PlayerSave save, long playerId, long ownerEpoch) {
            this.save = save;
            this.playerId = playerId;
            this.ownerEpoch = ownerEpoch;
        }

        @Override
        public void run() {
            runWithRetry(this);
        }

        boolean apply() {
            return save != null ? store.saveStateAndRelease(toRow(save)) : store.releaseOwnership(playerId, ownerEpoch);
        }

        String describe() {
            if (save == null) {
                return "释放归属 player=" + Long.toUnsignedString(playerId) + " epoch=" + ownerEpoch;
            }
            Vec3 p = save.position();
            return "玩家写回 player=" + Long.toUnsignedString(playerId) + " epoch=" + ownerEpoch
                    + " level=" + save.level() + " scene_config=" + save.sceneConfigId()
                    + " pos=(" + p.x() + "," + p.y() + "," + p.z() + ")";
        }
    }

    static PlayerData toData(PlayerRow row) {
        return new PlayerData(row.getPlayerId(), row.getOwnerEpoch(), row.getClassId(), row.getGender(),
                row.getAppearanceId(), row.getLevel(), row.getSceneConfigId(),
                new Vec3(row.getPosX(), row.getPosY(), row.getPosZ()));
    }

    static PlayerRow toRow(PlayerSave save) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(save.playerId());
        row.setOwnerEpoch(save.ownerEpoch());
        row.setLevel(save.level());
        row.setSceneConfigId(save.sceneConfigId());
        row.setPosX(save.position().x());
        row.setPosY(save.position().y());
        row.setPosZ(save.position().z());
        return row;
    }
}
