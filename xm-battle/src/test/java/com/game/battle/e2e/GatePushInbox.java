package com.game.battle.e2e;

import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.proto.PushTarget;
import com.game.proto.MessageContent;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 扮演一个 gate：在 Redis 在线目录里登记玩家（{@code xm:presence:{pid}}），订阅该 gate 的推送频道 {@code xm:gate-push:{zone}:{gate}}，
 * 按目标玩家收下 battle 发来的 {@code GatePush}（大厅公告 177 / 143 的回落，battle-node-spec §7.7）。只用于测试。
 */
final class GatePushInbox implements AutoCloseable {

    /** 一条到达的推送：原样的 {@code GatePush} 与它携带的客户端消息（按序）。 */
    record Delivery(GatePush push, List<MessageContent> contents) {
    }

    final int zoneId;
    final int gateNodeId;
    final String gateInstanceId;
    private final RedissonClient redis;
    private final PlayerPresenceDirectory presence;
    private final RTopic topic;
    private final int listenerId;
    private final Map<Long, BlockingQueue<Delivery>> byPlayer = new ConcurrentHashMap<>();
    private final List<PlayerPresence> registered = new ArrayList<>();

    GatePushInbox(RedissonClient redis, int zoneId, int gateNodeId, String gateInstanceId) {
        this.redis = redis;
        this.zoneId = zoneId;
        this.gateNodeId = gateNodeId;
        this.gateInstanceId = gateInstanceId;
        this.presence = new PlayerPresenceDirectory(redis);
        this.topic = redis.getTopic(RedisKeys.gatePushTopic(zoneId, gateNodeId), ByteArrayCodec.INSTANCE);
        this.listenerId = topic.addListener(byte[].class, (channel, bytes) -> onMessage(bytes));
    }

    private void onMessage(byte[] bytes) {
        GatePush push;
        List<MessageContent> contents = new ArrayList<>();
        try {
            push = GatePush.parseFrom(bytes);
            switch (push.getActionCase()) {
                case MESSAGE_CONTENT -> contents.add(MessageContent.parseFrom(push.getMessageContent()));
                case MESSAGE_BATCH -> {
                    for (ByteString content : push.getMessageBatch().getMessageContentsList()) {
                        contents.add(MessageContent.parseFrom(content));
                    }
                }
                default -> {
                }
            }
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("gate 推送解析失败", e);
        }
        Delivery delivery = new Delivery(push, List.copyOf(contents));
        for (PushTarget target : push.getTargetsList()) {
            queue(target.getPlayerId()).add(delivery);
        }
    }

    private BlockingQueue<Delivery> queue(long playerId) {
        return byPlayer.computeIfAbsent(playerId, id -> new LinkedBlockingQueue<>());
    }

    /** 把玩家登记为在本 gate 的 {@code sessionId} 会话上（同 gate 进场后写在线目录）。 */
    void online(long playerId, int sessionId) {
        PlayerPresence entry = PlayerPresence.newBuilder()
                .setPlayerId(playerId)
                .setZoneId(zoneId)
                .setGateNodeId(gateNodeId)
                .setGateInstanceId(gateInstanceId)
                .setSessionId(sessionId)
                .setOnlineSinceMs(System.currentTimeMillis())
                .setOwnerEpoch(1)
                .build();
        presence.putAsync(entry).toCompletableFuture().join();
        synchronized (registered) {
            registered.add(entry);
        }
    }

    /** 下一条发给 {@code playerId} 的推送；{@code timeout} 内没有就断言失败。 */
    Delivery next(long playerId, Duration timeout) throws InterruptedException {
        Delivery delivery = queue(playerId).poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (delivery == null) {
            throw new AssertionError(timeout + " 内 gate 没有收到发给 " + playerId + " 的推送");
        }
        return delivery;
    }

    /** {@code window} 内没有发给 {@code playerId} 的推送。 */
    void expectNone(long playerId, Duration window) throws InterruptedException {
        Delivery delivery = queue(playerId).poll(window.toMillis(), TimeUnit.MILLISECONDS);
        if (delivery != null) {
            throw new AssertionError(window + " 内不该再收到发给 " + playerId + " 的推送，实际收到 " + delivery.push().getActionCase());
        }
    }

    @Override
    public void close() {
        topic.removeListener(listenerId);
        synchronized (registered) {
            for (PlayerPresence entry : registered) {
                presence.removeAsync(entry).toCompletableFuture().join();
            }
            registered.clear();
        }
    }

    RedissonClient redis() {
        return redis;
    }
}
