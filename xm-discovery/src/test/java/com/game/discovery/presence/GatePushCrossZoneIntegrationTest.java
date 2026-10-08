package com.game.discovery.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPushes.Outcome;
import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PlayerPresence;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/**
 * 同号 gate 跨 zone 的推送在<b>真 Redis</b> pub/sub 上（spectate-spec §2.7 的 Z5、§2.8；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）：
 * 两个订阅者各扮一个 zone 的 1 号 gate，订阅的就是 gate 进程启动时订的那个频道（{@link RedisKeys#gatePushTopic}）。在线目录经生产用的
 * {@link PlayerPresenceDirectory} 写入。钉住：发给 zone 2 玩家的消息只有 zone 2 的那台收得到，zone 1 的同号 gate 一条也收不到。
 *
 * <p>用 DB 13；两个 zone 号与玩家号取随机大数（真实的 zone 号是个位数），频道只属于本用例；结束时只删自己写的在线目录条目。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GatePushCrossZoneIntegrationTest {

    /** 两台 1 号 gate 发出的第一个会话号（{@code 节点号 << 17 | 序号}）。 */
    private static final int SAME_SESSION = (1 << 17) | 1;
    private static final long BASE = (1L << 51) + ThreadLocalRandom.current().nextLong(1L << 40) * 8;
    private static final long A = BASE + 1;
    private static final long B = BASE + 2;
    private static final int ZONE1 = 1_000_000 + ThreadLocalRandom.current().nextInt(100_000_000) * 2;
    private static final int ZONE2 = ZONE1 + 1;

    private static RedissonClient redis;
    private static PlayerPresenceDirectory directory;
    private static PlayerPushes pushes;

    private final LinkedBlockingQueue<GatePush> zone1Gate1 = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<GatePush> zone2Gate1 = new LinkedBlockingQueue<>();
    private RTopic topic1;
    private RTopic topic2;
    private int listener1;
    private int listener2;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        directory = new PlayerPresenceDirectory(redis);
        pushes = new PlayerPushes(redis, directory);
    }

    @AfterAll
    static void cleanup() {
        redis.getBucket(RedisKeys.presence(A)).delete();
        redis.getBucket(RedisKeys.presence(B)).delete();
        redis.shutdown();
    }

    @BeforeEach
    void subscribe() throws Exception {
        topic1 = redis.getTopic(RedisKeys.gatePushTopic(ZONE1, 1), ByteArrayCodec.INSTANCE);
        topic2 = redis.getTopic(RedisKeys.gatePushTopic(ZONE2, 1), ByteArrayCodec.INSTANCE);
        listener1 = topic1.addListener(byte[].class, (channel, message) -> zone1Gate1.add(parse(message)));
        listener2 = topic2.addListener(byte[].class, (channel, message) -> zone2Gate1.add(parse(message)));
        // 两人的 gate 节点号与会话号全部相同，只有 zone 与 gate 实例不同
        await(directory.putAsync(presence(A, ZONE1, "it-gate-z1")));
        await(directory.putAsync(presence(B, ZONE2, "it-gate-z2")));
    }

    @AfterEach
    void unsubscribe() {
        topic1.removeListener(listener1);
        if (listener2 >= 0) {
            topic2.removeListener(listener2);
        }
    }

    private static GatePush parse(byte[] message) {
        try {
            return GatePush.parseFrom(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlayerPresence presence(long playerId, int zoneId, String gateInstance) {
        return PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setGateNodeId(1).setGateInstanceId(gateInstance)
                .setSessionId(SAME_SESSION).setOnlineSinceMs(1000).setOwnerEpoch(1).build();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void 频道名带zone_两个zone的1号gate订的是两个不同的频道() {
        assertThat(RedisKeys.gatePushTopic(ZONE1, 1)).isEqualTo("xm:gate-push:" + ZONE1 + ":1");
        assertThat(RedisKeys.gatePushTopic(ZONE2, 1)).isEqualTo("xm:gate-push:" + ZONE2 + ":1").isNotEqualTo(RedisKeys.gatePushTopic(ZONE1, 1));
    }

    @Test
    void 按序推给zone2的玩家_只有zone2的1号gate收到_zone1的同号gate一条也没有() throws Exception {
        BytesValue assigned = BytesValue.of(ByteString.copyFromUtf8("177"));
        BytesValue start = BytesValue.of(ByteString.copyFromUtf8("143"));

        assertThat(await(pushes.pushAllToPlayer(B, List.of(assigned, start)))).isEqualTo(Outcome.SENT);

        GatePush push = zone2Gate1.poll(5, TimeUnit.SECONDS);
        assertThat(push).as("zone 2 的 1 号 gate 收到了").isNotNull();
        assertThat(push.getGateInstanceId()).isEqualTo("it-gate-z2");
        assertThat(push.getMessageBatch().getMessageContentsList()).containsExactly(assigned.toByteString(), start.toByteString());
        assertThat(push.getTargetsList()).singleElement().satisfies(target -> {
            assertThat(target.getSessionId()).isEqualTo(SAME_SESSION);
            assertThat(target.getPlayerId()).isEqualTo(B);
        });
        assertThat(zone1Gate1.poll(300, TimeUnit.MILLISECONDS)).as("zone 1 的 1 号 gate：节点号相同，但不是它的频道").isNull();
    }

    @Test
    void 同一条消息推给两个zone的两名玩家_按zone分成两条_各到各的1号gate() throws Exception {
        BytesValue content = BytesValue.of(ByteString.copyFromUtf8("evt"));

        Map<Long, Outcome> outcomes = await(pushes.pushToPlayers(List.of(A, B), content));

        assertThat(outcomes).containsEntry(A, Outcome.SENT).containsEntry(B, Outcome.SENT);
        GatePush toZone1 = zone1Gate1.poll(5, TimeUnit.SECONDS);
        GatePush toZone2 = zone2Gate1.poll(5, TimeUnit.SECONDS);
        assertThat(toZone1).isNotNull();
        assertThat(toZone2).isNotNull();
        assertThat(toZone1.getGateInstanceId()).isEqualTo("it-gate-z1");
        assertThat(toZone1.getTargetsList()).singleElement().satisfies(target -> assertThat(target.getPlayerId()).isEqualTo(A));
        assertThat(toZone2.getGateInstanceId()).isEqualTo("it-gate-z2");
        assertThat(toZone2.getTargetsList()).singleElement().satisfies(target -> assertThat(target.getPlayerId()).isEqualTo(B));
        assertThat(zone1Gate1.poll(300, TimeUnit.MILLISECONDS)).as("每个 gate 只收到自己那一条").isNull();
        assertThat(zone2Gate1.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void zone2的1号gate不在了_发给它的玩家报GATE_UNREACHABLE_不会转给zone1的同号gate() throws Exception {
        topic2.removeListener(listener2);
        listener2 = -1;

        assertThat(await(pushes.pushToPlayer(B, BytesValue.of(ByteString.copyFromUtf8("evt"))))).isEqualTo(Outcome.GATE_UNREACHABLE);

        assertThat(zone1Gate1.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }
}
