package com.game.match.gather;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 开局管线的入口（match-spec §3、§9.3、§9.6）：五个入口（凑单弹组、PVE_SOLO、切磋、整队开战、活动开战）都把一份 {@link GatherPlan} 交给它。
 * 每次 gather 跑在自己的虚拟线程上（最长约 90 s 的串行 RPC 链），全局在途上限 {@code xm.match.gather-max-inflight}。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@link #launch} <b>不阻塞、不抛异常</b>（参数由 {@link GatherPlan} 的构造器校验过），可以在任何线程上调——包括凑单线程与工作线程。</li>
 *   <li>返回的 future <b>永不异常完成</b>、一定会完成：成功、失败（补偿做完之后）或 {@link GatherOutcome#OVERLOADED}（拿不到在途许可，立即完成，
 *       没有任何副作用——没发号、没冻结人、没碰票据之外的东西；票据仍按 {@link GatherPlan#onFail()} 处置，这样各入口对过载不必特殊处理）。
 *       管线内部的任何意外异常都收敛成 {@link GatherOutcome#INTERNAL}。</li>
 *   <li>future 在 gather 的线程上完成：挂在它上面的回调<b>不得阻塞</b>（要做 I/O 就自己切到有界执行器上，例如切磋的推送用 {@code match-push}）。</li>
 *   <li>gather 一旦启动就<b>不可取消</b>：调用方丢掉 future、调用方的请求超时、整队开战的长挂 RPC 断开，都不影响它跑完并自己收尾票据。</li>
 *   <li>每个 plan 只 launch 一次。同一批票据 launch 两次的后果由票据的 CAS 兜住，但会白冻结一轮人。</li>
 * </ul>
 */
public interface GatherLauncher {

    /** 启动一次 gather。 */
    CompletableFuture<GatherResult> launch(GatherPlan plan);

    /**
     * 此刻还剩多少在途许可（≥ 0）。只是参考值（读完之后可能变）：凑单在弹组之前看它（为 0 就暂停，队列原样保留）、PVE_SOLO 在建票之前看它
     * （为 0 回 16004，不建票）；真正拿许可发生在 {@link #launch} 里。不阻塞。
     */
    int availablePermits();

    /**
     * 停机用：等在途的 gather 全部结束，至多等 {@code timeout}。调用之前应已停止产生新的 gather（停凑单、撤 Dubbo 导出）。
     *
     * @return true = 已经没有在途的 gather；false = 超时仍有（直接放弃：票据按 matched TTL 自愈，scene 按备战期限解冻）
     */
    boolean awaitIdle(Duration timeout);
}
