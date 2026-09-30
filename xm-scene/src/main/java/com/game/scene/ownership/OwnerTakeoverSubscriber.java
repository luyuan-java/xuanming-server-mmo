package com.game.scene.ownership;

import com.game.api.proto.OwnerTakeover;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.function.BiConsumer;
import org.redisson.api.RTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅 login 发来的归属接管请求（Redis pub/sub，频道 {@code RedisKeys.ownerTakeoverTopic()}，消息 {@code xm.api.OwnerTakeover}），
 * 交给场景逻辑处理（{@code SceneWorld.onTakeoverRequested}）。
 *
 * <p>回调在 Redisson 的网络线程上，不得阻塞：只解析并投递。投递目标由调用方给（负责切到逻辑线程）。
 * 解析失败的消息丢弃并告警（频道只在内网，来源是 login）。
 */
public final class OwnerTakeoverSubscriber {

    private static final Logger log = LoggerFactory.getLogger(OwnerTakeoverSubscriber.class);

    private final RTopic topic;
    private final BiConsumer<Long, Long> onTakeover;
    private int listenerId = -1;

    /**
     * @param onTakeover (player_id, owner_epoch)，实现负责投递到逻辑线程且不阻塞
     */
    public OwnerTakeoverSubscriber(RTopic topic, BiConsumer<Long, Long> onTakeover) {
        this.topic = topic;
        this.onTakeover = onTakeover;
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
                log.warn("取消订阅归属接管请求失败", e);
            }
            listenerId = -1;
        }
    }

    /** 处理一条消息（Redisson 网络线程上；包内可见供测试直接驱动）。 */
    void accept(byte[] message) {
        OwnerTakeover takeover;
        try {
            takeover = OwnerTakeover.parseFrom(message);
        } catch (InvalidProtocolBufferException e) {
            log.warn("归属接管请求不是合法的 OwnerTakeover，丢弃 长度={}", message == null ? -1 : message.length);
            return;
        }
        if (takeover.getPlayerId() == 0 || takeover.getOwnerEpoch() == 0) {
            log.warn("归属接管请求缺 player_id 或 owner_epoch，丢弃");
            return;
        }
        onTakeover.accept(takeover.getPlayerId(), takeover.getOwnerEpoch());
    }
}
