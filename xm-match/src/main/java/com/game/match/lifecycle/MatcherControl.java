package com.game.match.lifecycle;

/**
 * 凑单循环（{@code match-matcher}）的启停口：{@link MatchLifecycle} 只经它启停凑单，不认识具体的凑单类。凑单包把自己的调度器做成这个接口的 bean
 * （一个进程恰好一个，生产实现是 {@code matcher.MatcherRunner}）；上下文里没有这个 bean 时进程拒绝启动。
 *
 * <p><b>契约</b>（match-spec §9.8）：
 * <ul>
 *   <li>实现<b>不得自己启停</b>（不带 {@code initMethod} / {@code destroyMethod}、不实现 {@code Lifecycle}）：凑单必须在 Dubbo 导出之后才开始、
 *       在 Dubbo 撤导出之前就停下，这个次序只有 {@link MatchLifecycle} 能保证。</li>
 *   <li>{@link #start} 只调一次，在启动线程上，不阻塞（只是排上定时任务）；抛异常 = 拒绝启动。</li>
 *   <li>{@link #stop} <b>等当前这一轮凑单结束</b>再返回（有界：一轮的最坏耗时受凑单锁 TTL 与 Redis 超时约束），之后不再弹任何一组；
 *       已经交给 {@code GatherLauncher} 的 gather 不归它管。幂等；没 {@link #start} 过也能调；不抛异常。</li>
 * </ul>
 */
public interface MatcherControl {

    /** 开始按 {@code xm.match.matcher.interval} 一轮一轮地凑单。 */
    void start();

    /** 停止凑单并等当前一轮结束。 */
    void stop();
}
