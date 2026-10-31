package com.game.discovery.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import com.game.proto.contracts.kafka.eSpectateFrameKind;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RShardedTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * 发布一帧时两条命令的先后（不连 Redis；真实的连发顺序见 {@link SpectateFrameChannelIntegrationTest}）：紧挨着发出的命令走不同的连接、没有先后可言，
 * 所以 SET 没有结局之前不能发 SPUBLISH，否则「先订阅、后读键」的订阅方会漏帧。
 */
class SpectateFrameChannelTest {

    private static final long BATTLE = 7001;
    private static final SpectateFrameEvent FRAME = SpectateFrameEvent.newBuilder().setBattleId(BATTLE).setBattleInstanceId("inst").setRoundIndex(3)
            .setKind(eSpectateFrameKind.SPECTATE_FRAME_TURN).setClientFrame(ByteString.copyFromUtf8("frame")).setDeadlineMs(1).build();

    private final RedissonClient redis = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<byte[]> bucket = mock(RBucket.class);
    private final RShardedTopic topic = mock(RShardedTopic.class);
    private final CompletableFuture<Void> stateWrite = new CompletableFuture<>();
    private final CompletableFuture<Long> publish = new CompletableFuture<>();
    private final SpectateFrameChannel channel = new SpectateFrameChannel(redis, new RedisKeys(GameNamespace.of("game")));

    @BeforeEach
    void wire() {
        when(redis.<byte[]>getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(redis.getShardedTopic(anyString(), any(Codec.class))).thenReturn(topic);
        when(bucket.setAsync(any(byte[].class), any(Duration.class))).thenReturn(new CompletableFutureWrapper<Void>(stateWrite));
        when(topic.publishAsync(any())).thenReturn(new CompletableFutureWrapper<Long>(publish));
    }

    @Test
    void SET完成之前不发布_两条都完成才算这一帧完成() {
        CompletionStage<Void> done = channel.publish(FRAME, Duration.ofSeconds(30));

        verify(topic, never()).publishAsync(any());
        assertThat(done.toCompletableFuture()).isNotDone();

        stateWrite.complete(null);
        verify(topic).publishAsync(any());
        assertThat(done.toCompletableFuture()).as("SPUBLISH 还没有结局").isNotDone();

        publish.complete(1L);
        assertThat(done.toCompletableFuture()).isCompleted();
    }

    @Test
    void SET失败也照发_这一帧按失败完成() {
        CompletionStage<Void> done = channel.publish(FRAME, Duration.ofSeconds(30));

        stateWrite.completeExceptionally(new IllegalStateException("redis timeout"));
        verify(topic).publishAsync(any());
        publish.complete(1L);

        assertThat(done.toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void SPUBLISH失败_这一帧按失败完成() {
        CompletionStage<Void> done = channel.publish(FRAME, Duration.ofSeconds(30));

        stateWrite.complete(null);
        publish.completeExceptionally(new IllegalStateException("redis timeout"));

        assertThat(done.toCompletableFuture()).isCompletedExceptionally();
    }
}
