package com.game.match.lifecycle;

/**
 * 观战清扫器（{@code match-spectate-sweeper}：定时摘掉可观战索引里过期的成员、采样索引大小；spectate-spec §4.7）的启停口：
 * {@link MatchLifecycle} 只经它启停，不认识具体的清扫类。观战包把自己的清扫器做成这个接口的 bean（一个进程恰好一个）；
 * 上下文里没有这个 bean 时进程拒绝启动。
 *
 * <p><b>契约</b>（与 {@link MatcherControl} 同一个模子）：
 * <ul>
 *   <li>实现<b>不得自己启停</b>（不带 {@code initMethod}、不实现 {@code Lifecycle}、构造时不排任务）：后台件一律等 Dubbo 导出之后、
 *       由 {@link MatchLifecycle} 在启动第 8 步统一起——启动失败的进程不该先去碰 Redis，上下文测试里也不会有一条清扫线程在后台打替身。
 *       可以带一个幂等的 {@code destroyMethod} 兜底（上下文起到一半失败时 {@link #stop} 不会被调）。</li>
 *   <li>{@link #start} 只调一次，在启动线程上，<b>不阻塞</b>（只是排上定时任务：间隔 {@code xm.match.spectate.sweep-interval}，
 *       用 {@code scheduleWithFixedDelay}）；抛异常 = 拒绝启动。停机已经开始时不会被调。</li>
 *   <li>{@link #stop} 在停机线程上被调用，排在「等在途 163」之后、「等在途 gather」之前：停止排新的一轮，并<b>当场中断</b>手上这一轮
 *       ——<b>不等它自己做完</b>：这一步串在两个各 10 s 的等待之间，它等多久，最坏停机时长就多多久（lead 裁决 4：不得超过原有的阶段上限）；
 *       清扫是幂等的只删操作，做一半被打断无害。中断之后只等清扫线程退出（守约的实现立刻退出；另设一个很短的上限防违约）；
 *       之后不再碰 Redis。幂等；没 {@link #start} 过也能调；<b>不抛异常</b>。</li>
 *   <li>清扫的每一轮必须自己兜住一切 {@code Throwable}（JDK 的定时器遇到异常会永久停掉这个任务）；多实例重复执行幂等，不抢锁。</li>
 * </ul>
 */
public interface SweeperControl {

    /** 什么都不做的清扫口（测试里不关心清扫时用；生产装配里没有它）。 */
    SweeperControl NOOP = new SweeperControl() {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    /** 开始按 {@code xm.match.spectate.sweep-interval} 一轮一轮地清扫。 */
    void start();

    /** 停止清扫：不再排新的一轮，中断当前这一轮并等清扫线程退出。 */
    void stop();
}
