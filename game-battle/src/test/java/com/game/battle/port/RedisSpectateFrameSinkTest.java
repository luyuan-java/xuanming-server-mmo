package com.game.battle.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.BattleClock;
import com.game.discovery.spectate.SpectateFrameChannel;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 观战中继帧的 Redis 端口（发布本身见 game-discovery 的 {@code SpectateFrameChannelIntegrationTest}，房间行为见 {@code SpectateRelayRoomTest}）。 */
class RedisSpectateFrameSinkTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SpectateFrameChannel channel = mock(SpectateFrameChannel.class);
    private final RedisSpectateFrameSink sink = new RedisSpectateFrameSink(channel, BattleClock.SYSTEM, new BattleMetrics(meters));

    private double frames(String result) {
        return meters.get("game.battle.spectate.relay.frames").tag("result", result).counter().count();
    }

    @Test
    void 通道没有结局时这一帧没有完成_成功完成并计数() {
        CompletableFuture<Void> published = new CompletableFuture<>();
        when(channel.publish(any(), any())).thenReturn(published);

        CompletionStage<Void> done = sink.publish(SpectateFrameEvent.getDefaultInstance());

        assertThat(done.toCompletableFuture()).as("房间靠它串行：没有结局就不能放行下一帧").isNotDone();
        published.complete(null);
        assertThat(done.toCompletableFuture()).isCompleted();
        assertThat(frames("ok")).isEqualTo(1);
    }

    @Test
    void 通道失败_这一帧照样完成_只计数() {
        when(channel.publish(any(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("redis timeout")));

        assertThat(sink.publish(SpectateFrameEvent.getDefaultInstance()).toCompletableFuture()).isCompleted();

        assertThat(frames("failed")).isEqualTo(1);
    }

    @Test
    void 通道同步抛异常_不往外抛_这一帧照样完成() {
        when(channel.publish(any(), any())).thenThrow(new IllegalStateException("redis client closed"));

        assertThat(sink.publish(SpectateFrameEvent.getDefaultInstance()).toCompletableFuture()).isCompleted();

        assertThat(frames("failed")).isEqualTo(1);
    }

    @Test
    void 最新帧键的TTL是距期限的时长加余量_期限已过只留余量_余量盖得住gate的终局墓碑() {
        long now = 1_800_000_000_000L;

        assertThat(RedisSpectateFrameSink.stateTtl(now + 60_000, now)).isEqualTo(RedisSpectateFrameSink.STATE_TTL_MARGIN.plusSeconds(60));
        assertThat(RedisSpectateFrameSink.stateTtl(now - 60_000, now)).isEqualTo(RedisSpectateFrameSink.STATE_TTL_MARGIN);
        assertThat(RedisSpectateFrameSink.STATE_TTL_MARGIN).isGreaterThanOrEqualTo(Duration.ofMinutes(10));
    }
}
