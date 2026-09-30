package com.game.login.ownership;

import com.game.api.proto.OwnerTakeover;
import com.game.discovery.RedisKeys;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OwnerTakeovers} 的 Redis pub/sub 实现：往 {@link RedisKeys#ownerTakeoverTopic()} 发一条
 * {@code xm.api.OwnerTakeover}，全部 scene 节点订阅，持有该归属的那个处理。异步发布，不占调用线程。
 */
public final class RedisOwnerTakeovers implements OwnerTakeovers {

    private static final Logger log = LoggerFactory.getLogger(RedisOwnerTakeovers.class);

    private final RTopic topic;

    public RedisOwnerTakeovers(RedissonClient redis) {
        this.topic = redis.getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE);
    }

    @Override
    public void request(long playerId, long heldEpoch) {
        byte[] message = OwnerTakeover.newBuilder().setPlayerId(playerId).setOwnerEpoch(heldEpoch).build().toByteArray();
        try {
            topic.publishAsync(message).whenComplete((receivers, error) -> {
                if (error != null) {
                    log.warn("发布归属接管请求失败（等租约过期兜底） player={} epoch={}: {}", playerId, heldEpoch, error.toString());
                } else {
                    log.debug("已发布归属接管请求 player={} epoch={} 订阅者={}", playerId, heldEpoch, receivers);
                }
            });
        } catch (RuntimeException e) {
            log.warn("发布归属接管请求失败（等租约过期兜底） player={} epoch={}: {}", playerId, heldEpoch, e.toString());
        }
    }
}
