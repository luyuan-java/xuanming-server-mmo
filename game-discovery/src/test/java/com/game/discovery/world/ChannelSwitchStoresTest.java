package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;

class ChannelSwitchStoresTest {
    private final RedisKeys keys = new RedisKeys(GameNamespace.of("channeltest"));
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RScript script = mock(RScript.class);

    @Test
    void 目录读取用主节点和原始字节_缺失与故障不同() {
        when(redis.getScript(ByteArrayCodec.INSTANCE)).thenReturn(script);
        var store = new RedissonWorldChannelDirectoryStore(redis, keys);
        assertThat(store.read(1, 7)).isNull();
        byte[] bytes = {0, -1, -128, 10};
        when(script.<byte[]>eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE),
                eq(List.of(keys.worldChannelDirectory(1, 7))))).thenReturn(bytes);
        assertThat(store.read(1, 7)).containsExactly(bytes);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.VALUE), anyList()))
                .thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(() -> store.read(1, 7)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 发布携带同槽键_十进制大版本与原始pb_失去围栏返回false() {
        when(redis.getScript(ByteArrayCodec.INSTANCE)).thenReturn(script);
        when(script.<Long>eval(eq(RScript.Mode.READ_WRITE), eq(RedissonWorldChannelDirectoryStore.PUBLISH),
                eq(RScript.ReturnType.INTEGER), anyList(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.<List<String>>getArgument(3)).containsExactly(keys.worldLeader(1),
                    keys.worldPlanVersion(1), keys.worldChannelDirectory(1, 7));
            assertThat(invocation.<byte[]>getArgument(4)).isEqualTo("leader".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(invocation.<byte[]>getArgument(5)).isEqualTo("9007199254740993".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(invocation.<byte[]>getArgument(6)).containsExactly((byte) 0, (byte) -1);
            assertThat(invocation.<byte[]>getArgument(7)).isEqualTo("2000".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return 1L;
        });
        var store = new RedissonWorldChannelDirectoryStore(redis, keys);
        assertThat(store.publish(1, "leader", 9_007_199_254_740_993L, 7, new byte[] {0, -1}, Duration.ofSeconds(2))).isTrue();
        reset(script);
        when(script.<Long>eval(eq(RScript.Mode.READ_WRITE), eq(RedissonWorldChannelDirectoryStore.PUBLISH),
                eq(RScript.ReturnType.INTEGER), anyList(), any(), any(), any(), any())).thenReturn(0L);
        assertThat(store.publish(1, "leader", 1, 7, new byte[0], Duration.ofSeconds(2))).isFalse();
        assertThatThrownBy(() -> store.publish(1, "leader", 1, 7, new byte[0], Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 冷却主节点读取_failclosed_写入有界ttl且全局玩家键() {
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.<Long>eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER),
                eq(List.of(keys.channelSwitchCooldown(-1))))).thenReturn(0L, 1L, null);
        var store = new RedissonChannelSwitchCooldown(redis, keys);
        assertThat(store.active(-1)).isFalse();
        assertThat(store.active(-1)).isTrue();
        assertThatThrownBy(() -> store.active(-1)).isInstanceOf(IllegalStateException.class);
        RBucket<Object> bucket = mock(RBucket.class);
        when(redis.getBucket(keys.channelSwitchCooldown(-1), StringCodec.INSTANCE)).thenReturn(bucket);
        store.start(-1, Duration.ofSeconds(10));
        verify(bucket).set("1", Duration.ofSeconds(10));
        assertThat(keys.channelSwitchCooldown(-1)).isEqualTo("channeltest:player:18446744073709551615:channel-switch-cooldown");
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofDays(2))) {
            assertThatThrownBy(() -> store.start(42, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoMoreInteractions(bucket);
    }
}
