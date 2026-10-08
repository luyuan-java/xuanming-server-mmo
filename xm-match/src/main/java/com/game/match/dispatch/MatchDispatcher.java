package com.game.match.dispatch;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.RequestResult;
import com.game.match.support.MatchTips;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按消息号把 {@link ClientCall} 派发给 {@link MatchMethodHandler}（match-spec §9.9、§8.1）。它实现的就是 {@link MatchMethodHandler} 注释里
 * 「派发器对一次调用做的事」那几条，逐条对应：
 * <ol>
 *   <li>没有处理器的号（含契约里没有的号）→ 信封 1003；</li>
 *   <li>截止 = 受理时刻 + 请求预算（含之后的排队时间）；</li>
 *   <li>{@link MatchMethodHandler#inline()} 的处理器在调用线程（Dubbo 线程）上当场执行；其余投到一个执行器上——处理器经
 *       {@link MatchMethodHandler#executor()} 给了自己的就用它（163 观战：虚拟线程 + 在途上限），没给（null）就是 {@link MatchWorkers}；</li>
 *   <li>执行器拒收（工作池满 / 163 的在途已满）、或轮到执行时预算已用完 → 不调 {@code handle}，按 {@link MatchMethodHandler#onOverload()} 回（M29）；</li>
 *   <li>{@code handle} 抛 {@link InvalidProtocolBufferException}（请求体解析失败）或未分类的 RuntimeException → 信封 1003。</li>
 * </ol>
 * 处理器的登记规则：方法名经 {@code MessageIdRegistry} 换成消息号；同一个方法两个处理器、或方法名不在契约的 {@code MatchService} 里，构造即失败。
 * 返回的 future 永不异常完成；不产生会话指令。线程安全。
 *
 * <p><b>发号租约真正丢失期间</b>（lead 裁决 2）：157 排队与 152 发起切磋不再交给各自的处理器，在调用线程上当场按「内部错误」口径回
 * （{@link LostLeaseRefusals}）；其余八个号照常。判定排在「有没有处理器」之后：没有处理器的号仍是信封 1003。
 */
public final class MatchDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MatchDispatcher.class);

    /** 租约丢失期间拒收日志的最小间隔：拒收的次数由玩家的请求量决定，逐条打会刷屏；丢失本身已有 ERROR 与健康检查。 */
    private static final long REFUSAL_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Map<Integer, MatchMethodHandler> handlers;
    private final Executor workers;
    private final MatchMetrics metrics;
    private final long budgetMillis;
    private final BooleanSupplier leaseLost;
    private final LostLeaseRefusals refusals;
    private final AtomicLong lastRefusalLogNanos = new AtomicLong(System.nanoTime() - REFUSAL_LOG_INTERVAL_NANOS);

    /** 不看租约的派发器（组件测试用；生产一律用带 {@code leaseLost} 的构造器）。 */
    public MatchDispatcher(MessageIdRegistry registry, Collection<? extends MatchMethodHandler> handlers, Executor workers, MatchMetrics metrics,
                           long budgetMillis) {
        this(registry, handlers, workers, metrics, budgetMillis, () -> false);
    }

    /**
     * @param handlers     全部处理器 bean（可以为空：这时每个号都回信封 1003）
     * @param workers      {@code match-worker} 工作池（测试可传同步执行器）：没有自带执行器的处理器都投到它上面
     * @param budgetMillis 整请求预算（{@code xm.match.request-budget}）
     * @param leaseLost    发号租约是否已真正丢失（生产为 {@code MatchIds::leaseLost}）；每次派发读一次，不得阻塞
     * @throws IllegalStateException 处理器的方法名不在契约的 {@code MatchService} 里，或同一个方法有两个处理器
     */
    public MatchDispatcher(MessageIdRegistry registry, Collection<? extends MatchMethodHandler> handlers, Executor workers, MatchMetrics metrics,
                           long budgetMillis, BooleanSupplier leaseLost) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.budgetMillis = budgetMillis;
        this.leaseLost = Objects.requireNonNull(leaseLost, "leaseLost");
        this.refusals = new LostLeaseRefusals(metrics);
        Map<Integer, MatchMethodHandler> byId = new HashMap<>();
        for (MatchMethodHandler handler : handlers) {
            String method = handler.method();
            if (!MatchMethods.ALL.contains(method)) {
                throw new IllegalStateException("处理器的方法名不在契约的 " + MatchMethods.SERVICE + " 里: " + method + "（" + handler.getClass().getName() + "）");
            }
            int messageId = registry.requireId(MatchMethods.SERVICE, method);
            MatchMethodHandler previous = byId.put(messageId, handler);
            if (previous != null) {
                throw new IllegalStateException("方法 " + method + " 有两个处理器: " + previous.getClass().getName() + " 与 " + handler.getClass().getName());
            }
        }
        this.handlers = Collections.unmodifiableMap(byId);
    }

    /** 已有处理器的消息号（升序；启动日志与测试用）。 */
    public Set<Integer> handledMessageIds() {
        return Collections.unmodifiableSet(new TreeSet<>(handlers.keySet()));
    }

    /** 契约的 {@code MatchService} 里还没有处理器的方法名（升序）：这些号一律回信封 1003。全部接齐时为空。 */
    public Set<String> unhandledMethods() {
        Set<String> missing = new TreeSet<>(MatchMethods.ALL);
        handlers.values().forEach(handler -> missing.remove(handler.method()));
        return Collections.unmodifiableSet(missing);
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        MatchMethodHandler handler = handlers.get(call.getMessageId());
        if (handler == null) {
            log.warn("[match] 没有处理器的消息号 message_id={} {}", call.getMessageId(), describe(call.getSession()));
            metrics.requestCompleted(sample, null, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(envelope(MatchTips.SERVICE_UNAVAILABLE));
        }
        Deadline deadline = Deadline.after(budgetMillis); // 受理时刻起算，含排队
        if (leaseLost.getAsBoolean()) {
            MatchMethodHandler refusal = refusals.refusalFor(handler.method()).orElse(null);
            if (refusal != null) {
                logRefusal(handler, call);
                return CompletableFuture.completedFuture(run(refusal, call, deadline, sample));
            }
        }
        if (handler.inline()) {
            return CompletableFuture.completedFuture(run(handler, call, deadline, sample));
        }
        Executor own;
        try {
            own = handler.executor();
        } catch (RuntimeException e) { // 违反约定：executor() 不该抛。按处理器异常收场，不让它漏成 Dubbo 层的错误
            log.error("[match] {} 的 executor() 抛了异常（回信封 1003）{}", handler.method(), describe(call.getSession()), e);
            metrics.requestCompleted(sample, handler.method(), RequestResult.ERROR);
            return CompletableFuture.completedFuture(envelope(MatchTips.SERVICE_UNAVAILABLE));
        }
        boolean shared = own == null;
        Executor executor = shared ? workers : own;
        CompletableFuture<ClientReply> reply = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    reply.complete(deadline.expired()
                            ? overloaded(handler, call, sample, shared ? "请求在工作队列里等过了预算" : "请求在处理器自己的执行器里等过了预算")
                            : run(handler, call, deadline, sample));
                } catch (Error e) { // run / overloaded 已兜住 RuntimeException；这里只防 Error 让 future 永不完成
                    reply.complete(envelope(MatchTips.SERVICE_UNAVAILABLE));
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            reply.complete(overloaded(handler, call, sample, shared ? "工作队列已满" : "处理器自己的执行器拒收（在途已满或已关闭）"));
        }
        return reply;
    }

    /** 调处理器并把它的应答翻成 {@link ClientReply}；解析失败与未分类异常都变成信封 1003。 */
    private ClientReply run(MatchMethodHandler handler, ClientCall call, Deadline deadline, Timer.Sample sample) {
        Reply reply;
        try {
            reply = Objects.requireNonNull(handler.handle(call.getSession(), call.getBody(), deadline), "处理器返回了空应答");
        } catch (InvalidProtocolBufferException e) {
            log.warn("[match] 请求体解析失败 method={} {}: {}", handler.method(), describe(call.getSession()), e.getMessage());
            metrics.requestCompleted(sample, handler.method(), RequestResult.BAD_REQUEST);
            return envelope(MatchTips.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("[match] {} 处理失败（回信封 1003）{}", handler.method(), describe(call.getSession()), e);
            metrics.requestCompleted(sample, handler.method(), RequestResult.ERROR);
            return envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
        metrics.requestCompleted(sample, handler.method(), reply instanceof Reply.Envelope ? RequestResult.FAILED : RequestResult.OK);
        return toClientReply(reply);
    }

    /** 过载：不调 {@code handle}，按该方法自己的过载应答回（它抛异常或回空就退到信封 1003）。 */
    private ClientReply overloaded(MatchMethodHandler handler, ClientCall call, Timer.Sample sample, String why) {
        log.warn("[match] {}，按过载应答 method={} {}", why, handler.method(), describe(call.getSession()));
        metrics.requestCompleted(sample, handler.method(), RequestResult.OVERLOADED);
        try {
            return toClientReply(Objects.requireNonNull(handler.onOverload(), "处理器返回了空的过载应答"));
        } catch (RuntimeException e) {
            log.error("[match] {} 的过载应答出错（回信封 1003）", handler.method(), e);
            return envelope(MatchTips.SERVICE_UNAVAILABLE);
        }
    }

    /** 租约丢失期间的拒收：每 10 s 至多一条 WARN，其余 DEBUG。 */
    private void logRefusal(MatchMethodHandler handler, ClientCall call) {
        long now = System.nanoTime();
        long last = lastRefusalLogNanos.get();
        if (now - last >= REFUSAL_LOG_INTERVAL_NANOS && lastRefusalLogNanos.compareAndSet(last, now)) {
            log.warn("[match] 发号租约已丢失：拒收 {}（回 in-band 16004；每 10 s 至多一条本日志）{}——需要重启本进程", handler.method(),
                    describe(call.getSession()));
        } else {
            log.debug("[match] 发号租约已丢失：拒收 {} {}", handler.method(), describe(call.getSession()));
        }
    }

    private static ClientReply toClientReply(Reply reply) {
        if (reply instanceof Reply.Envelope envelope) {
            return envelope(envelope.tipId());
        }
        return ClientReply.newBuilder().setBody(((Reply.Body) reply).bytes()).build();
    }

    /** 信封：只设 tip_id，不带 parameters。 */
    private static ClientReply envelope(int tipId) {
        return ClientReply.newBuilder().setTipId(tipId).build();
    }

    static String describe(SessionContext session) {
        return "gate=" + session.getGateNodeId() + " session=" + session.getSessionId() + " player=" + Long.toUnsignedString(session.getPlayerId());
    }
}
