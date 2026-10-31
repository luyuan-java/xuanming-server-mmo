package com.game.battle.port;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.BattleClock;
import com.game.discovery.spectate.SpectateFrameChannel;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SpectateFrameSink} 的生产实现：Redis 分片 pub/sub 加最新帧键（{@link SpectateFrameChannel}）。最新帧键的 TTL = 距战斗期限的时长 +
 * {@link #STATE_TTL_MARGIN}：覆盖整场战斗，也覆盖 gate 的 10 分钟终局墓碑，迟到的订阅者还读得到 166。
 *
 * <p>线程：{@link #publish} 在 battle 逻辑线程或同一场上一帧的 Redisson 回调线程上调用（房间按场串行，见 {@code BattleRoom.spectateTail}），只发起异步命令；
 * 结局在 Redisson 回调线程上只计数、采样打日志，不碰房间。Redisson 的每条命令都有超时（{@code RedisProperties.worstCaseCommandMillis}），所以返回的 stage 一定会完成。
 */
public final class RedisSpectateFrameSink implements SpectateFrameSink {

    private static final Logger log = LoggerFactory.getLogger(RedisSpectateFrameSink.class);

    static final Duration STATE_TTL_MARGIN = Duration.ofMinutes(10);
    /** 失败日志的采样掩码：第 1、1025、2049 … 次打一行（Redis 故障时每回合每场一次，逐条打会放大成日志风暴）。 */
    private static final long LOG_SAMPLE_MASK = 0x3FF;

    private final SpectateFrameChannel channel;
    private final BattleClock clock;
    private final BattleMetrics metrics;
    private final AtomicLong failures = new AtomicLong();

    public RedisSpectateFrameSink(SpectateFrameChannel channel, BattleClock clock, BattleMetrics metrics) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public CompletionStage<Void> publish(SpectateFrameEvent frame) {
        try {
            return channel.publish(frame, stateTtl(frame.getDeadlineMs(), clock.epochMillis())).<Void>handle((ignored, error) -> {
                if (error == null) {
                    metrics.spectateRelayFrame(true);
                } else {
                    failed(frame, error);
                }
                return null;
            });
        } catch (RuntimeException e) {
            failed(frame, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 期限已过（收尾帧可能如此）只留余量。 */
    static Duration stateTtl(long deadlineMs, long nowMs) {
        return STATE_TTL_MARGIN.plusMillis(Math.max(0, deadlineMs - nowMs));
    }

    private void failed(SpectateFrameEvent frame, Throwable error) {
        metrics.spectateRelayFrame(false);
        long n = failures.getAndIncrement();
        if ((n & LOG_SAMPLE_MASK) == 0) {
            log.warn("观战中继帧发布失败（至多一次，下一帧自愈；每 1024 次采样一条） battle_id={} kind={} round={} total={}: {}",
                    Long.toUnsignedString(frame.getBattleId()), frame.getKind(), frame.getRoundIndex(), n + 1, error.toString());
        }
    }
}
