package com.game.login.dispatch;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.login.metrics.LoginMetrics;
import com.game.login.metrics.LoginMetrics.RequestResult;
import com.game.proto.TipInfoMessage;
import com.game.table.CommonErrorTip;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Timer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按消息号把 {@link ClientCall} 派发给 {@code ClientPlayerLogin} 的处理器。
 *
 * <p>消息号只从 {@link MessageIdRegistry}（message_id.txt）取，不在代码里写数字；构造时逐个核对处理器声明的请求 /
 * 应答类型与契约一致，不一致（同步产物与代码脱节）直接启动失败。
 *
 * <p>失败的三个层次：
 * <ul>
 *   <li><b>业务失败</b>：处理器放进应答体 {@code error_message}，这里原样转发；</li>
 *   <li><b>内部故障</b>（处理器异常、工作队列满）：带 {@code error_message} 的方法回 1003 kServiceUnavailable 的应答体，
 *       空应答类型的方法不回包。不回「信封错误 + 空 body」：机器人不看信封，会把空 body 当成成功；</li>
 *   <li><b>传输层失败</b>（消息号不认识、请求体解析失败）：只填 {@link ClientReply#getTipId()}。</li>
 * </ul>
 * 每个请求按处理器方法名与上述结果计一次耗时（{@code xm.login.requests}，见 {@link LoginMetrics}）。
 * 返回的 future 永不异常完成。所有处理都在 login 工作线程池上执行，调用线程（Dubbo 线程）只负责投递。
 */
public final class ClientMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClientMessageDispatcher.class);

    /** proto/login/login.proto 里客户端服务的裸名（message_id.txt 的键前缀）。 */
    public static final String SERVICE = "ClientPlayerLogin";

    /** 应答体里表达业务失败的字段名（{@link ClientMessageHandler} 契约：字段 1 {@code error_message}）。 */
    static final String ERROR_MESSAGE_FIELD = "error_message";

    private final MessageIdRegistry registry;
    private final Map<Integer, Route<?>> routes;
    private final Executor executor;
    private final LoginMetrics metrics;

    /**
     * @param metrics 每个请求按处理器方法名与结果计耗时
     * @throws IllegalStateException 方法在 message_id.txt 里缺号、类型与契约不符，或两个处理器声明了同一方法
     */
    public ClientMessageDispatcher(MessageIdRegistry registry, List<? extends ClientMessageHandler<?>> handlers,
                                   Executor executor, LoginMetrics metrics) {
        this.registry = registry;
        this.executor = executor;
        this.metrics = metrics;
        Map<Integer, Route<?>> byId = new HashMap<>();
        for (ClientMessageHandler<?> handler : handlers) {
            int id = registry.requireId(SERVICE, handler.methodName());
            MessageMethod method = registry.byId(id).orElseThrow();
            if (method.requestPrototype().getClass() != handler.requestType()
                    || method.responsePrototype().getClass() != handler.responseType()) {
                throw new IllegalStateException("处理器类型与契约不符: " + method.key()
                        + " 契约=" + method.requestPrototype().getClass().getName()
                        + "->" + method.responsePrototype().getClass().getName()
                        + " 处理器=" + handler.requestType().getName() + "->" + handler.responseType().getName());
            }
            if (byId.put(id, routeOf(handler, method.requestPrototype())) != null) {
                throw new IllegalStateException("重复的处理器: " + method.key());
            }
        }
        this.routes = Collections.unmodifiableMap(byId);
    }

    /** 已接管的消息号（测试与启动日志用）。 */
    public Set<Integer> routedMessageIds() {
        return routes.keySet();
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        int messageId = call.getMessageId();
        Route<?> route = routes.get(messageId);
        if (route == null) {
            metrics.requestCompleted(sample, LoginMetrics.UNROUTED, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(unsupported(call));
        }
        String method = route.handler().methodName();
        CompletableFuture<Outcome> outcome;
        try {
            outcome = CompletableFuture.supplyAsync(() -> route.invoke(call), executor).thenCompose(Function.identity());
        } catch (RejectedExecutionException e) {
            log.warn("login 工作队列已满，拒绝请求 message_id={} {}", messageId, describe(call.getSession()));
            metrics.requestCompleted(sample, method, RequestResult.OVERLOADED);
            return CompletableFuture.completedFuture(route.failure(CommonErrorTip.common_error.kServiceUnavailable_VALUE));
        }
        return outcome.handle((done, error) -> {
            if (error != null) {
                log.error("处理客户端消息失败 message_id={} {}", messageId, describe(call.getSession()), unwrap(error));
                metrics.requestCompleted(sample, method, RequestResult.INTERNAL_ERROR);
                return route.failure(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
            }
            metrics.requestCompleted(sample, method, done.result());
            return done.reply();
        });
    }

    private ClientReply unsupported(ClientCall call) {
        Optional<MessageMethod> known = registry.byId(call.getMessageId());
        // 契约里有、但本服务没实现（如 RefreshToken）→ 功能未开放；契约里都没有 → 消息号不存在。
        int tip = known.isPresent()
                ? CommonErrorTip.common_error.kFeatureUnavailable_VALUE
                : CommonErrorTip.common_error.kMessageIdNotFound_VALUE;
        log.warn("login 不处理的消息号 message_id={} 方法={} {}", call.getMessageId(),
                known.map(MessageMethod::key).orElse("<未知>"), describe(call.getSession()));
        return ClientReply.newBuilder().setTipId(tip).build();
    }

    static ClientReply toClientReply(HandlerReply reply) {
        ClientReply.Builder builder = ClientReply.newBuilder().addAllDirectives(reply.directives());
        reply.body().ifPresent(body -> builder.setBody(body.toByteString()));
        return builder.build();
    }

    /** 处理器应答的结果分类：应答体设置了 {@code error_message} 即业务拒绝，否则成功（含不回包的空应答）。 */
    static RequestResult resultOf(HandlerReply reply) {
        return reply.body().map(ClientMessageDispatcher::hasErrorMessage).orElse(false)
                ? RequestResult.BUSINESS_ERROR : RequestResult.OK;
    }

    private static boolean hasErrorMessage(Message body) {
        FieldDescriptor field = body.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
        return field != null && !field.isRepeated() && body.hasField(field);
    }

    static String describe(SessionContext session) {
        return "gate=" + session.getGateNodeId() + " session=" + session.getSessionId()
                + " account=" + session.getAccount() + " player=" + session.getPlayerId();
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static <R extends Message> Route<R> routeOf(ClientMessageHandler<R> handler, Message prototype) {
        return new Route<>(handler, prototype);
    }

    /** 一次派发的结果：给 gate 的应答 + 指标用的结果分类。 */
    private record Outcome(ClientReply reply, RequestResult result) {
    }

    /** 一个消息号的派发目标。{@code prototype} 来自契约注册表，构造时已核对其类型就是 {@code R}。 */
    private record Route<R extends Message>(ClientMessageHandler<R> handler, Message prototype) {

        CompletableFuture<Outcome> invoke(ClientCall call) {
            R request;
            try {
                request = handler.requestType().cast(prototype.getParserForType().parseFrom(call.getBody()));
            } catch (InvalidProtocolBufferException e) {
                log.warn("请求体解析失败 method={} {}: {}", handler.methodName(), describe(call.getSession()), e.getMessage());
                return CompletableFuture.completedFuture(new Outcome(ClientReply.newBuilder()
                        .setTipId(CommonErrorTip.common_error.kRequestMessageParseError_VALUE)
                        .build(), RequestResult.BAD_REQUEST));
            }
            return handler.handle(call.getSession(), request)
                    .thenApply(reply -> new Outcome(toClientReply(reply), resultOf(reply)));
        }

        ClientReply failure(int tipId) {
            TipInfoMessage tip = Tips.of(tipId);
            return handler.failureBody(tip)
                    .map(body -> ClientReply.newBuilder().setBody(body.toByteString()).build())
                    .orElse(ClientReply.getDefaultInstance());
        }
    }
}
