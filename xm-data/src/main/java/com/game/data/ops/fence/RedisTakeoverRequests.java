package com.game.data.ops.fence;

import com.game.api.proto.OwnerTakeover;
import com.game.discovery.RedisKeys;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让出请求的 Redis pub/sub 发布方：往 {@link RedisKeys#ownerTakeoverTopic()} 发 {@code xm.api.OwnerTakeover}，全部 scene 节点订阅，
 * 持有该 epoch 的实例写回、释放、推 23 {2017} 后断开（{@code SceneWorld.onTakeoverRequested}）。与 xm-login 的
 * {@code RedisOwnerTakeovers} 同一个频道、同一种消息（data-ops-spec §7.1 建议把发布部分挪成 xm-discovery 的共用件；
 * 批次 7.2b 期间 xm-discovery 由别的批次在改，先在 xm-data 内实现，频道与消息都取自共用定义，不另起协议）。
 * 异步发布，尽力而为、不抛异常（投递失败最坏等租约过期）。
 */
public final class RedisTakeoverRequests implements AdminOwnership.TakeoverRequests {

    private static final Logger log = LoggerFactory.getLogger(RedisTakeoverRequests.class);

    private final Supplier<RedissonClient> redis;

    public RedisTakeoverRequests(Supplier<RedissonClient> redis) {
        this.redis = redis;
    }

    @Override
    public void request(long playerId, long heldEpoch) {
        byte[] message = OwnerTakeover.newBuilder().setPlayerId(playerId).setOwnerEpoch(heldEpoch).build().toByteArray();
        try {
            redis.get().getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE).publishAsync(message)
                    .whenComplete((receivers, error) -> {
                        if (error != null) {
                            log.warn("运维发布归属让出请求失败（等租约过期兜底） player={} epoch={}：{}",
                                    Long.toUnsignedString(playerId), Long.toUnsignedString(heldEpoch), error.toString());
                        } else {
                            log.debug("运维已发布归属让出请求 player={} epoch={} 订阅者={}", Long.toUnsignedString(playerId),
                                    Long.toUnsignedString(heldEpoch), receivers);
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("运维发布归属让出请求失败（等租约过期兜底） player={} epoch={}：{}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(heldEpoch), e.toString());
        }
    }
}
