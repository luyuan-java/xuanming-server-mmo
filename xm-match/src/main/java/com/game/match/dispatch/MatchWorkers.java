package com.game.match.dispatch;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code match-worker} 工作池的投递口（match-spec §9.3）：固定线程、有界队列、满了就拒。<b>可以阻塞</b>等 Redis / MySQL 的请求级工作都投到它上面，
 * 不占 Dubbo 线程——客户端请求（由派发器投递）、{@code MatchTeamService} 的前三个方法与 {@code MatchInternalService}（由各自的 Dubbo 提供方投递）。
 * 抽成接口是为了让各包不依赖具体的线程池类：组件测试传 {@code Runnable::run}（当场执行）或自己的执行器即可。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@link #execute} <b>不阻塞</b>调用线程；队列已满或工作池已停时抛 {@link RejectedExecutionException}——调用方必须接住并按自己的「过载」口径应答
 *       （客户端请求见 {@code MatchMethodHandler.onOverload}；整队的三个方法回 INTERNAL / FAILED；活动开战回 INTERNAL），不得让调用悬着。</li>
 *   <li>任务在队列里可能等很久：<b>调用方在投递之前就定好截止</b>（受理时刻 + 预算），任务开始执行时先看截止，过了就按过载应答、不再做事。</li>
 *   <li>任务里抛出的异常不会回到投递方：任务自己负责完成它的 future。</li>
 *   <li>不要把长时间挂起的事放上来（gather 跑在自己的虚拟线程上；{@code runTeamGather} 只登记 future 就返回）。</li>
 * </ul>
 * 线程安全。
 */
@FunctionalInterface
public interface MatchWorkers extends Executor {

    /**
     * 投递一个任务。
     *
     * @throws RejectedExecutionException 队列已满或工作池已停
     */
    @Override
    void execute(Runnable task);
}
