package com.game.match.lifecycle;

import java.time.Duration;

/**
 * 在途的 163 观战请求的「等它们结束」口：{@link MatchLifecycle} 停机时只经它等，不认识观战包的执行器。163 不跑在 {@code match-worker} 上
 * （每个请求一条虚拟线程 + 在途上限，spectate-spec §4.9），排空工作池等不到它，所以单独要这个口。观战包把自己的执行器做成这个接口的 bean
 * （一个进程恰好一个）；上下文里没有这个 bean 时进程拒绝启动。
 *
 * <p><b>契约</b>（spectate-spec §4.11；lead 裁决 4）：
 * <ul>
 *   <li>{@link #awaitIdle} 在停机线程之外的一条辅助线程上被调用，与「排空 {@code match-worker}」<b>并行</b>；调用时 Dubbo 已撤导出，
 *       不会再有新的 163 进来。</li>
 *   <li><b>有界阻塞</b>：至多等 {@code timeout}（生产为 {@code MatchBudgets.SPECTATE_DRAIN_TIMEOUT_MS}：一次 163 自己至多跑到它的 4.5 s 预算到点），
 *       到点就返回 false，不得无限等；可以被中断（中断时尽快返回 false 并保留中断标志）。</li>
 *   <li>只是<b>等</b>：不取消、不打断在途的请求。超时放弃的后果是那几条请求可能在连接关闭之后才跑完——已抢到的观战标记留到 TTL
 *       （只是「可能在观战」的提示），客户端那边按 gate 的 5 s 超时收到信封 1003。</li>
 *   <li>幂等；一次 163 都没受理过也能调（立即返回 true）；不抛异常。线程安全。</li>
 * </ul>
 */
@FunctionalInterface
public interface InflightWatches {

    /**
     * 等在途的 163 全部结束。
     *
     * @param timeout 等待上限（正数）
     * @return true = 已经没有在途的 163；false = 到点仍有（放弃等待）
     */
    boolean awaitIdle(Duration timeout);
}
