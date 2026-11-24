package com.game.payment.rpc;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.contract.generated.service.ClientPlayerPaymentContractDispatcher;
import com.game.contract.generated.service.ClientPlayerPaymentHandler;
import com.game.payment.PaymentTips;
import com.game.payment.channel.ChannelTypes.ClientParams;
import com.game.payment.core.PaymentService;
import com.game.payment.core.PaymentService.CreateRequest;
import com.game.payment.core.PaymentService.CreateResult;
import com.game.payment.core.PaymentService.GetResult;
import com.game.payment.core.PaymentService.SubmitRequest;
import com.game.payment.core.PaymentService.SubmitResult;
import com.game.proto.payment.CreatePayIntentRequest;
import com.game.proto.payment.CreatePayIntentResponse;
import com.game.proto.payment.GetPayIntentRequest;
import com.game.proto.payment.GetPayIntentResponse;
import com.game.proto.payment.ListPayChannelsRequest;
import com.game.proto.payment.ListPayChannelsResponse;
import com.game.proto.payment.PayChannel;
import com.game.proto.payment.PayClientPlatform;
import com.game.proto.payment.SubmitPurchaseRequest;
import com.game.proto.payment.SubmitPurchaseResponse;
import com.game.table.CommonErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code ClientPlayerPayment}（396–399）Dubbo 入口（group {@link DubboGroups#PAYMENT}）。
 * 通过 {@code game-proto-codegen} 编译期生成的 {@link ClientPlayerPaymentContractDispatcher} 做零反射派发，
 * 并在独立有界业务线程池上执行阻塞 HTTP/SQL 操作，不占用 Dubbo I/O 线程。
 */
@DubboService(group = DubboGroups.PAYMENT)
public final class PaymentRpcService implements ClientMessageService, ClientPlayerPaymentHandler<Long> {

    private static final Logger log = LoggerFactory.getLogger(PaymentRpcService.class);

    private final PaymentService service;
    private final MessageIdRegistry registry;
    private final Executor executor;

    public PaymentRpcService(PaymentService service, MessageIdRegistry registry, Executor executor) {
        this.service = Objects.requireNonNull(service, "service");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<ClientReply> handle(ClientCall call) {
        int messageId = call.getMessageId();
        if (!ClientPlayerPaymentContractDispatcher.handles(messageId)) {
            Optional<MessageMethod> known = registry.byId(messageId);
            return CompletableFuture.completedFuture(ClientReply.newBuilder()
                    .setTipId(known.isPresent()
                            ? CommonErrorTip.common_error.kFeatureUnavailable_VALUE
                            : CommonErrorTip.common_error.kMessageIdNotFound_VALUE)
                    .build());
        }
        long playerId = call.getSession().getPlayerId();
        try {
            return CompletableFuture.supplyAsync(() -> execute(messageId, playerId, call), executor);
        } catch (RejectedExecutionException e) {
            log.warn("[payment] worker pool saturated rejecting message_id={}", messageId);
            return CompletableFuture.completedFuture(ClientReply.newBuilder()
                    .setTipId(PaymentTips.SERVICE_UNAVAILABLE)
                    .build());
        }
    }

    private ClientReply execute(int messageId, long playerId, ClientCall call) {
        try {
            Message response = ClientPlayerPaymentContractDispatcher.dispatch(messageId, playerId, call.getBody(), this);
            if (response == null) {
                return ClientReply.newBuilder().setTipId(CommonErrorTip.common_error.kFeatureUnavailable_VALUE).build();
            }
            return ClientReply.newBuilder().setBody(response.toByteString()).build();
        } catch (InvalidProtocolBufferException e) {
            log.warn("[payment] malformed request body message_id={}: {}", messageId, e.getMessage());
            return ClientReply.newBuilder().setTipId(PaymentTips.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.error("[payment] handling message_id={} failed: {}", messageId, e.getMessage(), e);
            return ClientReply.newBuilder().setTipId(PaymentTips.SERVICE_UNAVAILABLE).build();
        }
    }

    @Override
    public ListPayChannelsResponse listPayChannels(Long playerId, ListPayChannelsRequest req) {
        if (playerId == null || playerId == 0L) {
            return ListPayChannelsResponse.newBuilder()
                    .setErrorMessage(PaymentTips.tip(PaymentTips.INVALID_PARAMETER))
                    .build();
        }
        List<PayChannel> list = service.listChannels(platformName(req.getPlatform()));
        if (list == null) {
            return ListPayChannelsResponse.newBuilder()
                    .setErrorMessage(PaymentTips.tip(PaymentTips.INVALID_PARAMETER))
                    .build();
        }
        return ListPayChannelsResponse.newBuilder().addAllChannels(list).build();
    }

    @Override
    public CreatePayIntentResponse createPayIntent(Long playerId, CreatePayIntentRequest req) {
        long nowMs = service.clock().millis();
        if (playerId == null || playerId == 0L) {
            return CreatePayIntentResponse.newBuilder()
                    .setErrorMessage(PaymentTips.tip(PaymentTips.INVALID_PARAMETER))
                    .setServerNowMs(nowMs)
                    .build();
        }
        CreateResult r = service.createIntent(new CreateRequest(
                playerId, req.getOrderId(), req.getChannel(), platformName(req.getPlatform()),
                req.getLoginCode(), req.getDeviceOs()));
        CreatePayIntentResponse.Builder b = CreatePayIntentResponse.newBuilder().setServerNowMs(service.clock().millis());
        if (r.tip() != PaymentTips.OK) {
            b.setErrorMessage(PaymentTips.tip(r.tip()));
        }
        if (r.intent() != null) {
            b.setIntent(r.intent());
        }
        if (r.tip() == PaymentTips.OK && r.params() != null) {
            ClientParams p = r.params();
            if (p.wechat() != null) b.setWechatApp(p.wechat());
            if (p.alipay() != null) b.setAlipayApp(p.alipay());
            if (p.devPay() != null) b.setDevpay(p.devPay());
            if (p.wechatMinigame() != null) b.setWechatMinigame(p.wechatMinigame());
            if (p.douyin() != null) b.setDouyinMinigame(p.douyin());
            if (p.apple() != null) b.setApple(p.apple());
            if (p.google() != null) b.setGoogle(p.google());
        }
        return b.build();
    }

    @Override
    public GetPayIntentResponse getPayIntent(Long playerId, GetPayIntentRequest req) {
        if (playerId == null || playerId == 0L) {
            return GetPayIntentResponse.newBuilder()
                    .setErrorMessage(PaymentTips.tip(PaymentTips.INVALID_PARAMETER))
                    .setServerNowMs(service.clock().millis())
                    .build();
        }
        GetResult r = service.getIntent(playerId, req.getOrderId());
        GetPayIntentResponse.Builder b = GetPayIntentResponse.newBuilder().setServerNowMs(service.clock().millis());
        if (r.tip() != PaymentTips.OK) {
            b.setErrorMessage(PaymentTips.tip(r.tip()));
        }
        if (r.intent() != null) {
            b.setIntent(r.intent());
        }
        return b.build();
    }

    @Override
    public SubmitPurchaseResponse submitPurchase(Long playerId, SubmitPurchaseRequest req) {
        if (playerId == null || playerId == 0L) {
            return SubmitPurchaseResponse.newBuilder()
                    .setErrorMessage(PaymentTips.tip(PaymentTips.INVALID_PARAMETER))
                    .setServerNowMs(service.clock().millis())
                    .build();
        }
        SubmitResult r = service.submitPurchase(new SubmitRequest(
                playerId, req.getChannel(), req.getOrderId(),
                req.getAppleTransactionId(), req.getGooglePurchaseToken(), req.getGoogleProductId()));
        SubmitPurchaseResponse.Builder b = SubmitPurchaseResponse.newBuilder()
                .setFinishTransaction(r.tip() == PaymentTips.OK && r.finish())
                .setServerNowMs(service.clock().millis());
        if (r.tip() != PaymentTips.OK) {
            b.setErrorMessage(PaymentTips.tip(r.tip()));
        }
        if (r.intent() != null) {
            b.setIntent(r.intent());
        }
        return b.build();
    }

    private static String platformName(PayClientPlatform platform) {
        if (platform == null) {
            return "";
        }
        return switch (platform) {
            case PAY_CLIENT_PLATFORM_ANDROID_APP -> "android_app";
            case PAY_CLIENT_PLATFORM_IOS_APP -> "ios_app";
            case PAY_CLIENT_PLATFORM_WECHAT_MINIGAME -> "wechat_minigame";
            case PAY_CLIENT_PLATFORM_DOUYIN_MINIGAME -> "douyin_minigame";
            case PAY_CLIENT_PLATFORM_DESKTOP -> "desktop";
            default -> "";
        };
    }

    @Override
    public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }

    @Override
    public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
        return CompletableFuture.completedFuture(Ack.getDefaultInstance());
    }
}
