package com.game.discovery.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPushes.Outcome;
import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PlayerPresence;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/**
 * 在线目录（Lua 带条件删除 / 续期补回）与推送（按 gate 分组发布）的真 Redis 集成测试。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机大号 player_id / zone 隔离。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class PresenceIntegrationTest {

    private static final long BASE = (1L << 50) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final int ZONE = 9000 + ThreadLocalRandom.current().nextInt(1000);

    private static RedissonClient redis;
    private static PlayerPresenceDirectory directory;
    private static PlayerPushes pushes;

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
        for (long i = 0; i < 12; i++) {
            redis.getBucket(RedisKeys.presence(BASE + i)).delete();
        }
        redis.shutdown();
    }

    private static PlayerPresence entry(long player, int gateNode, String instance, int session) {
        return PlayerPresence.newBuilder().setPlayerId(player).setZoneId(ZONE).setGateNodeId(gateNode)
                .setGateInstanceId(instance).setSessionId(session).setOnlineSinceMs(1000).build();
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void 写入后可查_带TTL() throws Exception {
        PlayerPresence p = entry(BASE, 1, "gate-a", 11);
        await(directory.putAsync(p));

        assertThat(directory.find(BASE)).contains(p);
        assertThat(await(directory.findAsync(BASE))).contains(p);
        long ttl = redis.getBucket(RedisKeys.presence(BASE)).remainTimeToLive();
        assertThat(ttl).isPositive().isLessThanOrEqualTo(PlayerPresenceDirectory.TTL.toMillis());
    }

    @Test
    void 下线只撤销自己写的那份() throws Exception {
        PlayerPresence old = entry(BASE + 1, 1, "gate-a", 11);
        PlayerPresence relogged = entry(BASE + 1, 2, "gate-b", 22);
        await(directory.putAsync(old));
        await(directory.putAsync(relogged));

        assertThat(await(directory.removeAsync(old))).as("值已是别的会话").isFalse();
        assertThat(directory.find(BASE + 1)).contains(relogged);
        assertThat(await(directory.removeAsync(relogged))).isTrue();
        assertThat(directory.find(BASE + 1)).isEmpty();
    }

    @Test
    void 续期_自己的延长_丢了的补回_被覆盖的不动() throws Exception {
        PlayerPresence mine = entry(BASE + 2, 1, "gate-a", 11);
        PlayerPresence lost = entry(BASE + 3, 1, "gate-a", 12);
        PlayerPresence overwritten = entry(BASE + 4, 1, "gate-a", 13);
        PlayerPresence other = entry(BASE + 4, 2, "gate-b", 99);
        await(directory.putAsync(mine));
        redis.getBucket(RedisKeys.presence(BASE + 2)).expire(java.time.Duration.ofSeconds(5));
        await(directory.putAsync(other));

        assertThat(await(directory.refreshAsync(List.of(mine, lost, overwritten)))).as("补回 1 条").isEqualTo(1);

        assertThat(redis.getBucket(RedisKeys.presence(BASE + 2)).remainTimeToLive()).isGreaterThan(5_000);
        assertThat(directory.find(BASE + 3)).contains(lost);
        assertThat(directory.find(BASE + 4)).as("玩家已在别处，不抢回").contains(other);
    }

    @Test
    void 运维清空脚本缓存后续期仍可用() throws Exception {
        PlayerPresence p = entry(BASE + 2, 1, "gate-a", 11);
        await(directory.refreshAsync(List.of(p)));          // 先让脚本进缓存
        redis.getScript().scriptFlush();                     // SCRIPT FLUSH：服务端忘掉全部脚本
        redis.getBucket(RedisKeys.presence(BASE + 2)).delete();

        assertThat(await(directory.refreshAsync(List.of(p)))).as("NOSCRIPT 后自动重新加载并补回").isEqualTo(1);
        assertThat(directory.find(BASE + 2)).contains(p);
        assertThat(await(directory.removeAsync(p))).isTrue();
    }

    @Test
    void 批量查只含在线的_损坏条目按不在线() throws Exception {
        await(directory.putAsync(entry(BASE + 5, 1, "gate-a", 11)));
        redis.getBucket(RedisKeys.presence(BASE + 6), ByteArrayCodec.INSTANCE).set(new byte[] {(byte) 0xff, 0x01});

        Map<Long, PlayerPresence> online = directory.findAll(List.of(BASE + 5, BASE + 6, BASE + 7));
        assertThat(online).containsOnlyKeys(BASE + 5);
        assertThat(await(directory.findAllAsync(List.of(BASE + 5, BASE + 7)))).containsOnlyKeys(BASE + 5);
    }

    @Test
    void 推送按gate分组发布_带玩家栅栏_不在线的报OFFLINE() throws Exception {
        LinkedBlockingQueue<GatePush> gate1 = new LinkedBlockingQueue<>();
        RTopic topic = redis.getTopic(RedisKeys.gatePushTopic(ZONE, 1), ByteArrayCodec.INSTANCE);
        int listener = topic.addListener(byte[].class, (ch, msg) -> {
            try {
                gate1.add(GatePush.parseFrom(msg));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            await(directory.putAsync(entry(BASE + 8, 1, "gate-a", 31)));
            await(directory.putAsync(entry(BASE + 9, 1, "gate-a", 32)));
            BytesValue content = BytesValue.of(ByteString.copyFromUtf8("evt"));

            Map<Long, Outcome> outcomes = await(pushes.pushToPlayers(List.of(BASE + 8, BASE + 9, BASE + 7), content));
            assertThat(outcomes).containsEntry(BASE + 8, Outcome.SENT).containsEntry(BASE + 9, Outcome.SENT)
                    .containsEntry(BASE + 7, Outcome.OFFLINE);
            GatePush push = gate1.poll(5, TimeUnit.SECONDS);
            assertThat(push).isNotNull();
            assertThat(push.getGateInstanceId()).isEqualTo("gate-a");
            assertThat(push.getMessageContent()).isEqualTo(content.toByteString());
            assertThat(push.getTargetsList()).extracting(t -> t.getSessionId() + ":" + t.getPlayerId())
                    .containsExactlyInAnyOrder("31:" + (BASE + 8), "32:" + (BASE + 9));
            assertThat(gate1.poll(200, TimeUnit.MILLISECONDS)).as("同一 gate 只发一条").isNull();

            assertThat(await(pushes.kick(BASE + 8, 2017))).isEqualTo(Outcome.SENT);
            GatePush kick = gate1.poll(5, TimeUnit.SECONDS);
            assertThat(kick.getKickTipId()).isEqualTo(2017);
            assertThat(kick.getTargetsList()).singleElement().satisfies(t -> assertThat(t.getPlayerId()).isEqualTo(BASE + 8));
        } finally {
            topic.removeListener(listener);
        }
    }

    @Test
    void 按序推送_一次查目录一次发布_订阅端按原顺序收到整批() throws Exception {
        LinkedBlockingQueue<GatePush> gate3 = new LinkedBlockingQueue<>();
        RTopic topic = redis.getTopic(RedisKeys.gatePushTopic(ZONE, 3), ByteArrayCodec.INSTANCE);
        int listener = topic.addListener(byte[].class, (ch, msg) -> {
            try {
                gate3.add(GatePush.parseFrom(msg));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            await(directory.putAsync(entry(BASE + 10, 3, "gate-c", 41)));
            BytesValue assigned = BytesValue.of(ByteString.copyFromUtf8("177"));
            BytesValue start = BytesValue.of(ByteString.copyFromUtf8("143"));

            assertThat(await(pushes.pushAllToPlayer(BASE + 10, List.of(assigned, start)))).isEqualTo(Outcome.SENT);
            GatePush push = gate3.poll(5, TimeUnit.SECONDS);
            assertThat(push).isNotNull();
            assertThat(push.getActionCase()).isEqualTo(GatePush.ActionCase.MESSAGE_BATCH);
            assertThat(push.getGateInstanceId()).isEqualTo("gate-c");
            assertThat(push.getMessageBatch().getMessageContentsList())
                    .containsExactly(assigned.toByteString(), start.toByteString());
            assertThat(push.getTargetsList()).singleElement()
                    .satisfies(t -> assertThat(t.getSessionId() + ":" + t.getPlayerId()).isEqualTo("41:" + (BASE + 10)));
            assertThat(gate3.poll(200, TimeUnit.MILLISECONDS)).as("整批只发布一次").isNull();

            assertThat(await(pushes.pushAllToPlayer(BASE + 11, List.of(assigned)))).as("不在线").isEqualTo(Outcome.OFFLINE);
            assertThat(gate3.poll(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            topic.removeListener(listener);
        }
    }

    @Test
    void 按序推送空列表直接拒绝() {
        assertThatThrownBy(() -> pushes.pushAllToPlayer(BASE + 10, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 目标gate没有订阅者_报GATE_UNREACHABLE() throws Exception {
        await(directory.putAsync(entry(BASE + 7, 77, "gate-gone", 1)));
        assertThat(await(pushes.pushToPlayer(BASE + 7, BytesValue.of(ByteString.EMPTY)))).isEqualTo(Outcome.GATE_UNREACHABLE);
    }
}
