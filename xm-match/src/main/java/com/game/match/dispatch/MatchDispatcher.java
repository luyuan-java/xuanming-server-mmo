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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按消息号把 {@link ClientCall} 派发给 {@link MatchMethodHandler}（match-spec §9.9、§8.1）。它实现的就是 {@link MatchMethodHandler} 注释里
 * 「派发器对一次调用做的事」那几条，逐条对应：
 * <ol>
 *   <li>没有处理器的号（含契约里没有的号）→ 信封 1003；</li>
 *   <li>截止 = 受理时刻 + 请求预算（含之后的排队时间）；</li>
 *   <li>{@link MatchMethodHandler#inline()} 的处理器在调用线程（Dubbo 线程）上当场执行；其余投到 {@link MatchWorkers}；</li>
 *   <li>工作池拒收、或轮到执行时预算已用完 → 不调 {@code handle}，按 {@link MatchMethodHandler#onOverload()} 回（M29）；</li>
 *   <li>{@code handle} 抛 {@link InvalidProtocolBufferException}（请求体解析失败）或未分类的 RuntimeException → 信封 1003。</li>
 * </ol>
 * 处理器的登记规则：方法名经 {@code MessageIdRegistry} 换成消息号；同一个方法两个处理器、或方法名不在契约的 {@code MatchService} 里，构造即失败。
 * 返回的 future 永不异常完成；不产生会话指令。线程安全。
 */
public final class MatchDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MatchDispatcher.class);

    private final Map<Integer, MatchMethodHandler> handlers;
    private final Executor workers;
    private final MatchMetrics metrics;
    private final long budgetMillis;

    /**
     * @param handlers     全部处理器 bean（可以为空：这时每个号都回信封 1003）
     * @param workers      {@code match-worker} 工作池（测试可传同步执行器）
     * @param budgetMillis 整请求预算（{@code xm.match.request-budget}）
     * @throws IllegalStateException 处理器的方法名不在契约的 {@code MatchService} 里，或同一个方法有两个处理器
     */
    public MatchDispatcher(MessageIdRegistry registry, Collection<? extends MatchMethodHandler> handlers, Executor workers, MatchMetrics metrics,
                           long budgetMillis) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.budgetMillis = budgetMillis;
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

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        MatchMethodHandler handler = handlers.get(call.getMessageId());
        if (handler == null) {
            log.warn("[match] 没有处理器的消息号 message_id={} {}", call.getMessageId(), describe(call.getSession()));
            metrics.requestCompleted(sample, null, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(envelope(MatchTips.SERVICE_UNAVAILABLE));
        }
        Deadline deadline = Deadline.after(budgetMillis); // 受理时刻起算，含排队
        if (handler.inline()) {
            return CompletableFuture.completedFuture(run(handler, call, deadline, sample));
        }
        CompletableFuture<ClientReply> reply = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try {
                    reply.complete(deadline.expired()
                            ? overloaded(handler, call, sample, "请求在工作队列里等过了预算")
                            : run(handler, call, deadline, sample));
                } catch (Error e) { // run / overloaded 已兜住 RuntimeException；这里只防 Error 让 future 永不完成
                    reply.complete(envelope(MatchTips.SERVICE_UNAVAILABLE));
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            reply.complete(overloaded(handler, call, sample, "工作队列已满"));
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
