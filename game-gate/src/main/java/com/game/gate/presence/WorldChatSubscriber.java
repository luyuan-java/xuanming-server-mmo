package com.game.gate.presence;

import com.game.gate.session.ClientDispatcher;
import com.game.gate.session.ClientSession;
import com.game.gate.session.SessionRegistry;
import com.game.proto.MessageContent;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.redisson.api.RTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅全服世界频道广播频道（{@code RedisKeys#chatWorldTopic()}），将 {@code NotifyWorldChat}（383）与
 * {@code NotifyWorldChatRetracted}（435）投递给本机所有已进场在线会话。
 *
 * <p>普通世界聊天按每台 gate 10 条/秒、突发 20 条令牌桶限流（超额静默丢弃，真相以拉取历史为准）；
 * 撤回通知（合规下线必需）<b>永不限流</b>，立即全量广播。
 */
public final class WorldChatSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WorldChatSubscriber.class);

    public static final double DEFAULT_RATE_PER_SECOND = 10.0;
    public static final double DEFAULT_BURST = 20.0;

    private final RTopic topic;
    private final SessionRegistry sessions;
    private final ClientDispatcher dispatcher;
    private final int notifyWorldChatId;
    private final int notifyWorldChatRetractedId;
    private final double ratePerSecond;
    private final double burst;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;
    private int listenerId = -1;

    public WorldChatSubscriber(RTopic topic, SessionRegistry sessions, ClientDispatcher dispatcher,
                               int notifyWorldChatId, int notifyWorldChatRetractedId) {
        this(topic, sessions, dispatcher, notifyWorldChatId, notifyWorldChatRetractedId,
                DEFAULT_RATE_PER_SECOND, DEFAULT_BURST, System::nanoTime);
    }

    WorldChatSubscriber(RTopic topic, SessionRegistry sessions, ClientDispatcher dispatcher,
                        int notifyWorldChatId, int notifyWorldChatRetractedId,
                        double ratePerSecond, double burst, LongSupplier nanoClock) {
        this.topic = Objects.requireNonNull(topic, "topic");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.notifyWorldChatId = notifyWorldChatId;
        this.notifyWorldChatRetractedId = notifyWorldChatRetractedId;
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.tokens = burst;
        this.lastRefillNanos = nanoClock.getAsLong();
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
                log.warn("world chat unsubscribe failed: {}", e.toString());
            }
            listenerId = -1;
        }
    }

    /** 处理一条世界频道广播帧（Redisson 回调线程；包内可见供单测直驱）。 */
    void accept(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return;
        }
        MessageContent content;
        try {
            content = MessageContent.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            log.warn("world chat invalid envelope dropped len={}", raw.length);
            return;
        }
        int msgId = content.getMessageId();
        boolean isChat = msgId == notifyWorldChatId;
        boolean isRetract = msgId == notifyWorldChatRetractedId;
        if (!isChat && !isRetract) {
            return;
        }
        if (isChat && !tryConsumeChatToken()) {
            return;
        }
        MessageContent push = content.getId() == 0 ? content : content.toBuilder().setId(0).build();
        for (ClientSession session : sessions.all()) {
            session.execute(() -> dispatcher.deliverWorldChat(session, push));
        }
    }

    private synchronized boolean tryConsumeChatToken() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(burst, tokens + ratePerSecond * (elapsed / 1_000_000_000.0));
            lastRefillNanos = now;
        }
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }
}
