package com.game.scene;

import com.game.scene.storage.StoragePlayerRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停服写回的收尾顺序（从 {@link SceneNode#stop} 拆出来单独可测）：
 * <ol>
 *   <li>把「写回全部在场玩家」这一个任务投递到逻辑线程，用<b>整个</b>停服预算等它执行完——不是固定的 5s。
 *       逻辑线程排着很长的队时，写回任务排在队尾，等的就是它；</li>
 *   <li>写回任务执行完（全部写回都已交给存储线程池）<b>之后</b>才关存储线程池，再用剩余预算等写回落库；
 *       过去的顺序是「等写回超时 → 照样关池」，排队中的写回任务稍后执行时全部被池拒绝，整节点玩家的状态丢失；</li>
 *   <li>预算用完写回任务还没开始执行：取消它（取消后逻辑线程不会再执行它，不会在已关闭的池上逐个失败），
 *       记一条明确的 ERROR（带大致在线人数）；已经开始执行的再宽限一小会儿；</li>
 *   <li>存储线程池在剩余预算内没排空：{@code shutdownNow}，被丢弃的写任务逐条记 ERROR（带 player_id / epoch / 场景 / 坐标）。</li>
 * </ol>
 */
final class SceneShutdown {

    private static final Logger log = LoggerFactory.getLogger(SceneShutdown.class);

    /** 写回任务已在执行、只是没在预算内跑完时，额外宽限的时间（它只是把写回逐个交给存储线程池，很快）。 */
    static final Duration RUNNING_GRACE = Duration.ofSeconds(2);

    /**
     * @param writeBackRan     写回任务是否执行完
     * @param playersSubmitted 写回任务交给存储线程池的人数（没执行完为 0）
     * @param droppedTasks     预算用完时存储线程池里被丢弃的任务数
     */
    record Result(boolean writeBackRan, int playersSubmitted, int droppedTasks) {
    }

    private SceneShutdown() {
    }

    /**
     * @param logic         场景逻辑线程
     * @param writeBack     在逻辑线程上执行：写回全部在场玩家（交给存储线程池）并关链路，返回人数
     * @param storage       存储线程池
     * @param budget        总预算（{@code xm.scene.shutdown-save-timeout}）
     * @param approxPlayers 写回没执行时报告的大致在线人数（可从任意线程读）
     */
    static Result writeBackThenDrainStorage(ExecutorService logic, Callable<Integer> writeBack, ExecutorService storage,
                                            Duration budget, IntSupplier approxPlayers, LongSupplier nanoClock) {
        long deadline = nanoClock.getAsLong() + budget.toNanos();
        Integer submitted = runWriteBack(logic, writeBack, budget, approxPlayers, deadline, nanoClock);
        int dropped = drainStorage(storage, budget, deadline, nanoClock);
        return new Result(submitted != null, submitted == null ? 0 : submitted, dropped);
    }

    private static Integer runWriteBack(ExecutorService logic, Callable<Integer> writeBack, Duration budget,
                                        IntSupplier approxPlayers, long deadline, LongSupplier nanoClock) {
        Future<Integer> future;
        try {
            future = logic.submit(writeBack);
        } catch (RejectedExecutionException e) {
            log.error("停服写回无法投递到逻辑线程（已停止），在线玩家（约 {} 人）本次在线期间的状态丢失", approxPlayers.getAsInt());
            return null;
        }
        try {
            return future.get(remaining(deadline, nanoClock), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (future.cancel(false)) {
                log.error("停服写回在 {} 内没能在逻辑线程上开始执行（逻辑线程积压），已取消；在线玩家（约 {} 人）本次在线期间的状态丢失，"
                        + "归属等租约过期后可重新进入", budget, approxPlayers.getAsInt());
                return null;
            }
            // 已经在执行：只是把写回逐个交给存储线程池，再宽限一小会儿。
            try {
                return future.get(RUNNING_GRACE.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException again) {
                log.error("停服写回执行超时或失败", again);
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } catch (ExecutionException e) {
            log.error("停服写回在逻辑线程上失败", e.getCause());
            return null;
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static int drainStorage(ExecutorService storage, Duration budget, long deadline, LongSupplier nanoClock) {
        storage.shutdown();
        try {
            if (storage.awaitTermination(remaining(deadline, nanoClock), TimeUnit.NANOSECONDS)) {
                return 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Runnable> dropped = storage.shutdownNow();
        log.error("存储任务未在 {} 内全部完成，丢弃剩余 {} 个（需人工修复）", budget, dropped.size());
        for (Runnable task : dropped) {
            StoragePlayerRepository.describeDropped(task).ifPresent(what -> log.error("停服丢弃：{}", what));
        }
        return dropped.size();
    }

    private static long remaining(long deadline, LongSupplier nanoClock) {
        return Math.max(0, deadline - nanoClock.getAsLong());
    }
}
