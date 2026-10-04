package com.game.chat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.chat.metrics.ChatMetrics;
import com.game.chat.metrics.ChatMetrics.RequestResult;
import com.game.chat.service.ChatService;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.TipInfoMessage;
import com.game.proto.chat.PullChatHistoryRequest;
import com.game.proto.chat.SendChatRequest;
import com.game.table.CommonErrorTip;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按消息号把 {@link ClientCall} 派发给 {@link ChatService}（{@code ClientPlayerChat}：61 SendChat、28 PullChatHistory）。
 *
 * <p>与好友的准入不同（同基线）：chat 的会话拦截器对坏头 / player_id = 0 放行到逻辑层，由逻辑层回 in-band 1005，所以这里不拦会话。
 * 请求体解析失败回信封 1003（基线 gRPC 反序列化错误经路由服翻成 1003）；不认识的号 → 1013（契约里没有）/ 1006（有、不归 chat）。
 * 处理全程异步（只发 Redis 异步命令），不占 Dubbo 线程、不需要工作线程池；返回的 future 永不异常完成。
 */
public final class ChatDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChatDispatcher.class);

    public static final String SERVICE = "ClientPlayerChat";
    static final String SEND = "SendChat";
    static final String PULL = "PullChatHistory";

    private final MessageIdRegistry registry;
    private final ChatService service;
    private final ChatMetrics metrics;
    private final long budgetMillis;
    private final int sendId;
    private final int pullId;

    public ChatDispatcher(MessageIdRegistry registry, ChatService service, ChatMetrics metrics, long budgetMillis) {
        this.registry = registry;
        this.service = service;
        this.metrics = metrics;
        this.budgetMillis = budgetMillis;
        this.sendId = requireMethod(registry, SEND, SendChatRequest.class);
        this.pullId = requireMethod(registry, PULL, PullChatHistoryRequest.class);
    }

    private static int requireMethod(MessageIdRegistry registry, String method, Class<?> requestType) {
        int id = registry.requireId(SERVICE, method);
        MessageMethod contract = registry.byId(id).orElseThrow();
        if (contract.requestPrototype().getClass() != requestType) {
            throw new IllegalStateException("处理器类型与契约不符: " + contract.key());
        }
        return id;
    }

    public Set<Integer> routedMessageIds() {
        return Set.of(sendId, pullId);
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        int messageId = call.getMessageId();
        long me = call.hasSession() ? call.getSession().getPlayerId() : 0;
        try {
            if (messageId == sendId) {
                SendChatRequest request = SendChatRequest.parseFrom(call.getBody());
                return service.send(me, request, budgetMillis).toCompletableFuture()
                        .handle((response, error) -> finish(sample, SEND, response, error));
            }
            if (messageId == pullId) {
                PullChatHistoryRequest request = PullChatHistoryRequest.parseFrom(call.getBody());
                return service.pull(me, request, budgetMillis).toCompletableFuture()
                        .handle((response, error) -> finish(sample, PULL, response, error));
            }
        } catch (InvalidProtocolBufferException e) {
            log.warn("[chat] 请求体解析失败 message_id={} gate={} session={}: {}", messageId, call.getSession().getGateNodeId(),
                    call.getSession().getSessionId(), e.getMessage());
            metrics.requestCompleted(sample, messageId == sendId ? SEND : PULL, RequestResult.BAD_REQUEST);
            return CompletableFuture.completedFuture(envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE));
        }
        Optional<MessageMethod> known = registry.byId(messageId);
        log.warn("[chat] 不处理的消息号 message_id={} 方法={}", messageId, known.map(MessageMethod::key).orElse("<未知>"));
        metrics.requestCompleted(sample, ChatMetrics.UNROUTED, RequestResult.UNSUPPORTED);
        return CompletableFuture.completedFuture(envelope(known.isPresent()
                ? CommonErrorTip.common_error.kFeatureUnavailable_VALUE
                : CommonErrorTip.common_error.kMessageIdNotFound_VALUE));
    }

    private ClientReply finish(Timer.Sample sample, String method, Message response, Throwable error) {
        if (error != null) {
            // 服务层把每个依赖故障都定性成 in-band 1003；走到这里是程序错误
            log.error("[chat] {} 处理失败", method, error);
            metrics.requestCompleted(sample, method, RequestResult.INTERNAL_ERROR);
            return envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        }
        metrics.requestCompleted(sample, method, resultOf(response));
        return ClientReply.newBuilder().setBody(response.toByteString()).build();
    }

    static RequestResult resultOf(Message response) {
        FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        if (field == null || !response.hasField(field)) {
            return RequestResult.OK;
        }
        TipInfoMessage tip = (TipInfoMessage) response.getField(field);
        return tip.getId() == CommonErrorTip.common_error.kServiceUnavailable_VALUE
                ? RequestResult.INTERNAL_ERROR : RequestResult.BUSINESS_ERROR;
    }

    private static ClientReply envelope(int tipId) {
        return ClientReply.newBuilder().setTipId(tipId).build();
    }
}
