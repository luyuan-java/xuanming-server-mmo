package com.game.battle.room;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * battle 逻辑线程（{@code battle-logic}）的执行器与计时器（battle-node-spec §7.3、§7.6）。生产实现 {@link EventLoopBattleScheduler} 包装
 * 直连面 child group 里<b>唯一</b>的 Netty EventLoop——同一条线程既跑直连 I/O，也独占全部房间、直连会话与房间计时器；
 * 测试实现 {@code com.game.battle.testing.ManualBattleScheduler}（虚拟时间，{@code advance(ms)} 按到期顺序执行）。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #execute}：投递到逻辑线程，排在当前正在执行的任务<b>之后</b>（即使从逻辑线程自己调用也不会内联执行）；
 *       逻辑线程已关闭时抛 {@link RejectedExecutionException}（控制面据此回 {@code NOT_ALLOCATABLE(closed)} 或让 future 异常完成）。
 *       任何线程可调——Dubbo 线程、管理 Tomcat 线程就是经它把请求交给 {@code BattleRoomService} 的。</li>
 *   <li>{@link #after} / {@link #every}：在逻辑线程上延迟执行 / 周期执行，返回 {@link Cancellable}。任务里抛出的异常被捕获并打 ERROR，
 *       周期任务继续下一次（一个房间的缺陷不能停掉别的计时器）。延迟按单调时钟，不受墙钟跳变影响。</li>
 *   <li>计时器回调<b>不得</b>捕获「房间一定还在」的假设：回调执行时按 battle_id 重查房间并比较对象身份
 *       （{@code rooms.get(id) == room && !room.closed}，§11 N14）。</li>
 *   <li>{@link #inLoop()} / {@link #assertInLoop()}：{@code BattleRoomService} 的每个 public 方法开头断言在逻辑线程上。</li>
 * </ul>
 */
public interface BattleScheduler extends Executor {

    /** 当前线程是不是逻辑线程。 */
    boolean inLoop();

    /**
     * 投递一个任务到逻辑线程（排在当前任务之后）。
     *
     * @throws RejectedExecutionException 逻辑线程已关闭
     */
    @Override
    void execute(Runnable task);

    /**
     * {@code delayMs} 毫秒后在逻辑线程上执行一次（{@code delayMs ≤ 0} 等同于尽快执行，但不内联）。
     *
     * @throws RejectedExecutionException 逻辑线程已关闭
     */
    Cancellable after(long delayMs, Runnable task);

    /**
     * 每 {@code periodMs} 毫秒在逻辑线程上执行一次，第一次在 {@code periodMs} 之后（固定频率，基线 {@code RunEvery}）。
     *
     * @throws IllegalArgumentException   {@code periodMs ≤ 0}
     * @throws RejectedExecutionException 逻辑线程已关闭
     */
    Cancellable every(long periodMs, Runnable task);

    /** 逻辑线程队列里等待执行的任务数（指标 {@code xm_battle_logic_pending_tasks}；任何线程可调，不阻塞）。 */
    int pendingTasks();

    /**
     * 断言当前在逻辑线程上。
     *
     * @throws IllegalStateException 不在逻辑线程上（线程所有权被破坏是程序缺陷，必须立刻暴露）
     */
    default void assertInLoop() {
        if (!inLoop()) {
            throw new IllegalStateException("只许在 battle 逻辑线程上调用，当前线程 " + Thread.currentThread().getName());
        }
    }
}
