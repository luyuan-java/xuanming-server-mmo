package com.game.discovery.spectate;

import com.game.discovery.RedisKeys;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.redisson.api.RShardedTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 观战帧的 Redis 通道（mmorpg battle-spectate-tiered-fanout.md §15.7 的 Java 口径）：battle 发布、gate 订阅，一场一个分片 pub/sub 频道
 * （{@link RedisKeys#spectateChannel}）加一把存最新帧的键（{@link RedisKeys#spectateState}）。这是 Java 版把中继档与广播档合成一种机制的落点：
 * 只有有本机观众的 gate 才订阅这一场，没有广播 topic 那种「全服 gate 都要读」的放大，所以不需要逐 gate 挂接，也不需要全服配额。
 *
 * <p>Redis pub/sub 至多一次投递；每帧都带全量状态，丢一帧下一帧自愈，gate 按 (实例, round_index) 单调去重。
 *
 * <p><b>线程</b>：全部方法只发起异步命令、立即返回（发布的 SPUBLISH 在 SET 的回调线程上发出），可以在 battle 逻辑线程、Redisson 回调线程与 gate 的 hub 线程上调。无本地状态，线程安全。
 */
public final class SpectateFrameChannel {

    private final RedissonClient redis;
    private final RedisKeys keys;

    public SpectateFrameChannel(RedissonClient redis, RedisKeys keys) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** 一个已建立的频道订阅。 */
    public interface Subscription {

        /** 退订。幂等，失败只影响多收几帧（gate 丢弃没有条目的帧），不抛异常。 */
        void close();
    }

    /**
     * 发布一帧并写最新帧键。SET 完成之后才发 SPUBLISH（SET 失败也照发：帧本身比键重要）：Redisson 的一条命令从借到连接起占着它、应答回来才归还，
     * 紧挨着发出的两条命令走不同的连接，Redis 按 socket 可读的先后执行，所以不等前一条就没有先后可言。有了这个先后，订阅方「先订阅、后读键」才成立：
     * 无论读到的是这一帧还是上一帧，都不会漏掉发布之后才订阅的那一刻的状态（读到旧帧时由 gate 的单调去重丢弃，下一帧带全量状态）。
     * 两条命令不是原子的，失败只损失这一帧。
     *
     * <p><b>帧与帧之间的先后不归这里管</b>：同一场的下一帧要等这个 stage 完成才能发（battle 房间的 {@code spectateTail}），否则同样会乱序。
     *
     * @param stateTtl 最新帧键的 TTL（战斗期限 + 余量）
     * @return 两条命令都有结局时完成；任一失败异常完成（SET 与 SPUBLISH 都失败时报 SET 的）
     */
    public CompletionStage<Void> publish(SpectateFrameEvent frame, Duration stateTtl) {
        Objects.requireNonNull(frame, "frame");
        byte[] bytes = frame.toByteArray();
        long battleId = frame.getBattleId();
        return redis.<byte[]>getBucket(keys.spectateState(battleId), ByteArrayCodec.INSTANCE).setAsync(bytes, stateTtl)
                .<Throwable>handle((ignored, stateFailure) -> stateFailure)
                .thenCompose(stateFailure -> topic(battleId).publishAsync(bytes).<Void>handle((receivers, publishFailure) -> {
                    Throwable failure = stateFailure != null ? stateFailure : publishFailure;
                    if (failure != null) {
                        throw new CompletionException(failure);
                    }
                    return null;
                }));
    }

    /**
     * 分片订阅这一场的频道。
     *
     * @param frames 每条消息的字节（在 Redisson 的线程上回调：不得阻塞，要投递回调用方自己的线程）
     * @return 订阅建立后完成；失败异常完成（没有留下半截订阅）
     */
    public CompletionStage<Subscription> subscribe(long battleId, Consumer<byte[]> frames) {
        Objects.requireNonNull(frames, "frames");
        RShardedTopic topic = topic(battleId);
        return topic.addListenerAsync(byte[].class, (channel, message) -> frames.accept(message)).thenApply(listenerId -> () -> {
            try {
                topic.removeListenerAsync(listenerId);
            } catch (RuntimeException ignored) {
                // 客户端已关闭（进程退出中）：订阅随连接一起没了
            }
        });
    }

    /** 读这一场最新的帧；没有（没启用中继、或已过期）为空。 */
    public CompletionStage<Optional<byte[]>> readState(long battleId) {
        return redis.<byte[]>getBucket(keys.spectateState(battleId), ByteArrayCodec.INSTANCE).getAsync().thenApply(Optional::ofNullable);
    }

    private RShardedTopic topic(long battleId) {
        return redis.getShardedTopic(keys.spectateChannel(battleId), ByteArrayCodec.INSTANCE);
    }
}
