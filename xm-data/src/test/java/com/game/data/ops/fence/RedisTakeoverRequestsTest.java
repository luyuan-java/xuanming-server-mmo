package com.game.data.ops.fence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.OwnerTakeover;
import com.game.discovery.RedisKeys;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * 让出请求的发布方（缺省就跑，不连 Redis；真 Redis 上的往返见 {@link AdminOwnershipRedisIntegrationTest}）：频道、编解码器与消息必须和
 * xm-login 的 {@code RedisOwnerTakeovers} 一模一样——xm-scene 只订阅这一个频道、按 {@code OwnerTakeover.parseFrom} 解；
 * 而且尽力而为，任何失败都不抛给作业（最坏等租约过期）。
 */
class RedisTakeoverRequestsTest {

    @Test
    void 发到归属让出频道_字节编解码_消息是OwnerTakeover_uint64原样() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redis.getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE)).thenReturn(topic);
        when(topic.publishAsync(any())).thenReturn(new CompletableFutureWrapper<Long>(CompletableFuture.completedFuture(1L)));
        long player = 0xF000_0000_0000_1234L; // ≥ 2^63：按无符号传
        long epoch = 77;

        new RedisTakeoverRequests(() -> redis).request(player, epoch);

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(topic).publishAsync(message.capture());
        assertThat(message.getValue()).isInstanceOf(byte[].class);
        byte[] bytes = (byte[]) message.getValue();
        OwnerTakeover decoded = OwnerTakeover.parseFrom(bytes);
        assertThat(decoded.getPlayerId()).isEqualTo(player);
        assertThat(decoded.getOwnerEpoch()).isEqualTo(epoch);
        // 与 xm-login 对同一对 (玩家, epoch) 发的逐字节相同
        assertThat(bytes).isEqualTo(OwnerTakeover.newBuilder().setPlayerId(player).setOwnerEpoch(epoch).build().toByteArray());
    }

    @Test
    void 取不到Redis客户端_取不到频道_发布失败_都不抛给调用方() {
        // 懒加载的客户端建不出来（Redis 不可达）
        assertThatCode(() -> new RedisTakeoverRequests(() -> {
            throw new IllegalStateException("redissonClient 建不出来");
        }).request(1, 2)).doesNotThrowAnyException();

        RedissonClient noTopic = mock(RedissonClient.class);
        when(noTopic.getTopic(any(String.class), any())).thenThrow(new IllegalStateException("客户端已关闭"));
        assertThatCode(() -> new RedisTakeoverRequests(() -> noTopic).request(1, 2)).doesNotThrowAnyException();

        RedissonClient redis = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redis.getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE)).thenReturn(topic);
        when(topic.publishAsync(any())).thenReturn(new CompletableFutureWrapper<Long>(
                CompletableFuture.failedFuture(new IllegalStateException("READONLY 主库切换中"))));
        assertThatCode(() -> new RedisTakeoverRequests(() -> redis).request(1, 2)).doesNotThrowAnyException();
        verify(topic).publishAsync(any());
    }
}
