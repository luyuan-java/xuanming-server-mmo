package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.proto.AddObserverRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 按固定原落点直拨观众 RPC。同步方法在虚拟线程上有界等待；异步取消独立 3 秒预算。
 * 不重试；超时后对端仍可能执行。所有清退必须传完整持久原件，不提供无操作号的删除入口。
 */
public interface ObserverDialer {
    sealed interface Outcome {
        /** Add 的业务码；Remove 的 0 只表示 cancelled + 原操作 UUID + 固定实例均已验证。 */
        record Replied(int tipId) implements Outcome { }
        /** 请求确定未送达且旧进程可判死；它不是原操作的取消正证明。 */
        record Dead() implements Outcome { }
        /** 这次请求确定未送达；不据此推断以前的 Add 没有生效。 */
        record NotDelivered(String detail) implements Outcome { }
        /** 可能执行，或 Remove 回包没有原操作的完整取消证明。 */
        record Unknown(String detail) implements Outcome { }
    }
    Outcome add(BattlePlacement placement, AddObserverRequest request, Duration timeout, Deadline hardStop);
    Outcome remove(LocalObserverOperation original, String reason, Duration timeout, Deadline hardStop);
    /** 完成回调只做指标与 compare-delete 异步调度，不阻塞 I/O 线程。 */
    CompletableFuture<Outcome> removeAsync(LocalObserverOperation original, String reason);
}