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
 *   <li>关池之前（写回执行完之后）再等在途的跨节点交出 / 探测「结局已在逻辑线程上处理完」（{@link TransferSettlement}）：
 *       冻结中被写回移出的玩家，交出在写回之后才提交时，结局处理要提交「释放 E+1」（或先探测），逻辑线程此时还活着、结局照常送达，
 *       若池已关这笔释放就被拒、E+1 悬空到租约过期，还报一条假的写丢失（scene-handoff-spec §5.2、§5.5 停服行）。
 *       等待与落库同时进行（池没关，写回照常在跑），共用同一个预算；预算用完还没处理完就记 ERROR 照常关池；</li>
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

    /**
     * 等在途交出 / 探测的结局在逻辑线程上处理完（生产实现是
     * {@link StoragePlayerRepository#awaitTransfersSettled}）。在停服线程上调用，不在逻辑线程上。
     */
    @FunctionalInterface
    interface TransferSettlement {

        /** 没有跨节点交出的装配（单测）。 */
        TransferSettlement NONE = timeoutNanos -> 0;

        /** @return 时限到时仍未处理完的个数（0 = 全部处理完） */
        int await(long timeoutNanos) throws InterruptedException;
    }

    private SceneShutdown() {
    }

    /**
     * @param logic         场景逻辑线程
     * @param writeBack     在逻辑线程上执行：写回全部在场玩家（交给存储线程池）并关链路，返回人数
     * @param transfers     写回执行完之后、关池之前，等在途交出 / 探测的结局处理完
     * @param storage       存储线程池
     * @param budget        总预算（{@code xm.scene.shutdown-save-timeout}）
     * @param approxPlayers 写回没执行时报告的大致在线人数（可从任意线程读）
     */
    static Result writeBackThenDrainStorage(ExecutorService logic, Callable<Integer> writeBack,
                                            TransferSettlement transfers, ExecutorService storage, Duration budget,
                                            IntSupplier approxPlayers, LongSupplier nanoClock) {
        long deadline = nanoClock.getAsLong() + budget.toNanos();
        Integer submitted = runWriteBack(logic, writeBack, budget, approxPlayers, deadline, nanoClock);
        if (submitted != null) {
            // 写回没执行（逻辑线程已停 / 积压到预算用完）时不等：结局送不到逻辑线程（已停时由存储线程兜底释放），或预算已无剩余
            awaitTransfers(transfers, budget, deadline, nanoClock);
        }
        int dropped = drainStorage(storage, budget, deadline, nanoClock);
        return new Result(submitted != null, submitted == null ? 0 : submitted, dropped);
    }

    private static void awaitTransfers(TransferSettlement transfers, Duration budget, long deadline,
                                       LongSupplier nanoClock) {
        try {
            int unsettled = transfers.await(remaining(deadline, nanoClock));
            if (unsettled > 0) {
                log.error("停服预算 {} 内仍有 {} 个跨节点交出 / 探测的结局没在逻辑线程上处理完，照常关存储线程池；"
                        + "其中交出若已提交，新 epoch 可能释放不了，等租约过期后可重新进入（库里是冻结快照，数据不丢）", budget, unsettled);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
