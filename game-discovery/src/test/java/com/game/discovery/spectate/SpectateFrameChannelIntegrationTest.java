package com.game.discovery.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.proto.contracts.kafka.SpectateFrameEvent;
import com.game.proto.contracts.kafka.eSpectateFrameKind;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 观战帧通道在<b>真 Redis</b> 分片 pub/sub 上（缺省跳过：{@code -Dgame.it.redis=redis://127.0.0.1:6379}；SSUBSCRIBE 要 Redis 7+，K8s 钉 7.2，本地 compose 8.x，两版都要跑）。
 * Redisson 的 {@code getShardedTopic} 在单机模式下也发 SSUBSCRIBE / SPUBLISH，这里钉住它真的通。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class SpectateFrameChannelIntegrationTest {

    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("game"));
    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        redis.shutdown();
    }

    private static SpectateFrameEvent frame(long battleId, int round) {
        return SpectateFrameEvent.newBuilder().setBattleId(battleId).setBattleInstanceId("it-instance").setRoundIndex(round)
                .setKind(eSpectateFrameKind.SPECTATE_FRAME_TURN).setClientFrame(ByteString.copyFromUtf8("frame-" + round)).setDeadlineMs(1).build();
    }

    @Test
    void 先订阅后发布收得到_最新帧键读得回_退订后不再收() throws Exception {
        long battleId = System.nanoTime();
        SpectateFrameChannel channel = new SpectateFrameChannel(redis, KEYS);
        BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        assertThat(channel.readState(battleId).toCompletableFuture().get(5, TimeUnit.SECONDS)).isEmpty();

        SpectateFrameChannel.Subscription subscription = channel.subscribe(battleId, received::add).toCompletableFuture().get(5, TimeUnit.SECONDS);
        channel.publish(frame(battleId, 1), Duration.ofSeconds(30)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        byte[] message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("分片订阅收到了发布的帧").isNotNull();
        assertThat(SpectateFrameEvent.parseFrom(message)).isEqualTo(frame(battleId, 1));
        Optional<byte[]> state = channel.readState(battleId).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(state).isPresent();
        assertThat(SpectateFrameEvent.parseFrom(state.get())).isEqualTo(frame(battleId, 1));
        assertThat(redis.getBucket(KEYS.spectateState(battleId)).remainTimeToLive()).as("最新帧键带 TTL").isPositive();

        subscription.close();
        Thread.sleep(200);
        channel.publish(frame(battleId, 2), Duration.ofSeconds(30)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("退订之后不再收").isNull();
        assertThat(SpectateFrameEvent.parseFrom(channel.readState(battleId).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow()).getRoundIndex())
                .as("最新帧键仍随发布更新").isEqualTo(2);
    }

    @Test
    void 最后一个订阅者退订后立刻重订_重订之后的帧照样收到() throws Exception {
        long battleId = System.nanoTime();
        SpectateFrameChannel channel = new SpectateFrameChannel(redis, KEYS);
        BlockingQueue<byte[]> renewedFrames = new LinkedBlockingQueue<>();

        // 不等退订生效就重订：hub 退频道与下一个观众的订阅没有先后约束，Redisson 按频道的信号量把两者排开，两种先后都不能丢订阅
        SpectateFrameChannel.Subscription old = channel.subscribe(battleId, bytes -> { }).toCompletableFuture().get(5, TimeUnit.SECONDS);
        old.close();
        SpectateFrameChannel.Subscription renewed = channel.subscribe(battleId, renewedFrames::add).toCompletableFuture().get(5, TimeUnit.SECONDS);
        channel.publish(frame(battleId, 1), Duration.ofSeconds(30)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        byte[] message = renewedFrames.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("重订之后的帧收到了").isNotNull();
        assertThat(SpectateFrameEvent.parseFrom(message).getRoundIndex()).isEqualTo(1);
        renewed.close();
    }

    /** 真实发布端的串行方式（同 battle 房间的 {@code spectateTail}）：上一帧有结局才发下一帧，失败也算有结局。 */
    private static CompletionStage<Void> publishAfter(CompletionStage<Void> tail, SpectateFrameChannel channel, SpectateFrameEvent next) {
        return tail.<Void>handle((ignored, failure) -> null).thenCompose(ignored -> channel.publish(next, Duration.ofSeconds(30)));
    }

    @Test
    void 连发TURN加END_循环多次_到达顺序与发布顺序相同_最新帧键停在END() throws Exception {
        SpectateFrameChannel channel = new SpectateFrameChannel(redis, KEYS);
        for (int attempt = 0; attempt < 50; attempt++) {
            long battleId = System.nanoTime();
            BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
            SpectateFrameChannel.Subscription subscription = channel.subscribe(battleId, received::add).toCompletableFuture().get(5, TimeUnit.SECONDS);

            CompletionStage<Void> tail = CompletableFuture.completedFuture(null);
            tail = publishAfter(tail, channel, frame(battleId, 7));
            tail = publishAfter(tail, channel, frame(battleId, 8));
            tail = publishAfter(tail, channel, frame(battleId, 8).toBuilder().setKind(eSpectateFrameKind.SPECTATE_FRAME_END).build());
            tail.toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertThat(SpectateFrameEvent.parseFrom(received.poll(5, TimeUnit.SECONDS)).getRoundIndex()).as("第 %d 轮", attempt).isEqualTo(7);
            assertThat(SpectateFrameEvent.parseFrom(received.poll(5, TimeUnit.SECONDS)).getKind()).isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_TURN);
            assertThat(SpectateFrameEvent.parseFrom(received.poll(5, TimeUnit.SECONDS)).getKind()).as("END 没有越过最后一回合").isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_END);
            assertThat(SpectateFrameEvent.parseFrom(channel.readState(battleId).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow()).getKind())
                    .as("SET 也没有乱序：迟到的订阅者读到的是终局").isEqualTo(eSpectateFrameKind.SPECTATE_FRAME_END);
            subscription.close();
        }
    }

    @Test
    void 按gate的配置订300个频道都收得到帧() throws Exception {
        // 与 game-gate 的 application.yaml 同值；只换这一项，其余（订阅池 50）取缺省。库缺省的单连接 5 个频道到 250 个就订不上了
        Config gateLike = new Config();
        gateLike.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13).setSubscriptionsPerConnection(500);
        RedissonClient gateRedis = Redisson.create(gateLike);
        try {
            SpectateFrameChannel gate = new SpectateFrameChannel(gateRedis, KEYS);
            SpectateFrameChannel battle = new SpectateFrameChannel(redis, KEYS);
            long base = System.nanoTime();
            BlockingQueue<Long> received = new LinkedBlockingQueue<>();
            List<SpectateFrameChannel.Subscription> subscriptions = new ArrayList<>();
            for (long i = 0; i < 300; i++) {
                long battleId = base + i;
                subscriptions.add(gate.subscribe(battleId, bytes -> received.add(battleId)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            }
            for (long i = 0; i < 300; i++) {
                battle.publish(frame(base + i, 1), Duration.ofSeconds(30)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            Set<Long> distinct = new HashSet<>();
            for (int i = 0; i < 300; i++) {
                Long from = received.poll(5, TimeUnit.SECONDS);
                assertThat(from).as("第 %d 帧", i).isNotNull();
                distinct.add(from);
            }
            assertThat(distinct).as("300 个频道各收到一帧").hasSize(300);
            subscriptions.forEach(SpectateFrameChannel.Subscription::close);
        } finally {
            gateRedis.shutdown();
        }
    }
}
