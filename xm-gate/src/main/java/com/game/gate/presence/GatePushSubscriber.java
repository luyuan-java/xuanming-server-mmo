package com.game.gate.presence;

import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PushTarget;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.PushKind;
import com.game.gate.metrics.GateMetrics.PushResult;
import com.game.gate.session.ClientDispatcher;
import com.game.gate.session.ClientSession;
import com.game.gate.session.SessionRegistry;
import com.game.proto.MessageContent;
import com.google.protobuf.InvalidProtocolBufferException;
import org.redisson.api.RTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅本 gate 的推送频道（{@code RedisKeys.gatePushTopic(zone, 节点号)}，消息 {@code xm.api.GatePush}），
 * 把每个目标投递到会话所属 EventLoop，由 {@link ClientDispatcher} 按玩家栅栏核对后下发。
 *
 * <p>回调在 Redisson 网络线程上：只解析与投递，不读写会话状态。gate 实例不符（节点号被复用前的旧条目）、格式不对的整条丢弃。
 */
public final class GatePushSubscriber {

    private static final Logger log = LoggerFactory.getLogger(GatePushSubscriber.class);

    private final RTopic topic;
    private final String gateInstanceId;
    private final SessionRegistry sessions;
    private final ClientDispatcher dispatcher;
    private final GateMetrics metrics;
    private int listenerId = -1;

    public GatePushSubscriber(RTopic topic, String gateInstanceId, SessionRegistry sessions, ClientDispatcher dispatcher,
                              GateMetrics metrics) {
        this.topic = topic;
        this.gateInstanceId = gateInstanceId;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    public synchronized void start() {
        if (listenerId < 0) {
            listenerId = topic.addListener(byte[].class, (channel, message) -> accept(message));
        }
    }

    public synchronized void stop() {
        if (listenerId >= 0) {
            try {
                topic.removeListener(listenerId);
            } catch (RuntimeException e) {
                log.warn("取消订阅推送频道失败", e);
            }
            listenerId = -1;
        }
    }

    /** 处理一条推送（Redisson 网络线程上；包内可见供测试直接驱动）。 */
    void accept(byte[] message) {
        GatePush push;
        try {
            push = GatePush.parseFrom(message);
        } catch (InvalidProtocolBufferException e) {
            log.warn("推送不是合法的 GatePush，丢弃 长度={}", message == null ? -1 : message.length);
            metrics.push(PushKind.MESSAGE, PushResult.INVALID, 1);
            return;
        }
        PushKind kind = push.getActionCase() == GatePush.ActionCase.KICK_TIP_ID ? PushKind.KICK : PushKind.MESSAGE;
        int targets = Math.max(1, push.getTargetsCount());
        if (!gateInstanceId.equals(push.getGateInstanceId())) {
            log.info("推送指向别的 gate 实例（节点号复用前的旧条目），丢弃 目标实例={} 目标数={}",
                    push.getGateInstanceId(), push.getTargetsCount());
            metrics.push(kind, PushResult.STALE_INSTANCE, targets);
            return;
        }
        switch (push.getActionCase()) {
            case MESSAGE_CONTENT -> {
                MessageContent content;
                try {
                    // 先解析一遍再下发：向客户端发损坏的 body 会让客户端读循环卡死（同 scene 下行的处理）。
                    content = MessageContent.parseFrom(push.getMessageContent());
                } catch (InvalidProtocolBufferException e) {
                    log.warn("推送的 MessageContent 损坏，整条丢弃 目标数={}", push.getTargetsCount());
                    metrics.push(kind, PushResult.INVALID, targets);
                    return;
                }
                for (PushTarget target : push.getTargetsList()) {
                    dispatch(kind, target, s -> dispatcher.deliverPush(s, target.getPlayerId(), content));
                }
            }
            case KICK_TIP_ID -> {
                int tipId = push.getKickTipId();
                for (PushTarget target : push.getTargetsList()) {
                    dispatch(kind, target, s -> dispatcher.kickByServer(s, target.getPlayerId(), tipId));
                }
            }
            case ACTION_NOT_SET -> {
                log.warn("推送没有动作，丢弃 目标数={}", push.getTargetsCount());
                metrics.push(kind, PushResult.INVALID, targets);
            }
        }
    }

    private void dispatch(PushKind kind, PushTarget target, java.util.function.Function<ClientSession, PushResult> action) {
        ClientSession session = sessions.get(target.getSessionId());
        if (session == null) {
            metrics.push(kind, PushResult.NO_SESSION, 1);
            return;
        }
        session.execute(() -> metrics.push(kind, action.apply(session), 1));
    }
}
