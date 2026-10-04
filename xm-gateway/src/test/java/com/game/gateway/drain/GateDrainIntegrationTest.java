package com.game.gateway.drain;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.drain.GateDrainMarks;
import com.game.discovery.drain.GateDrainMarks.MarkResult;
import com.game.gateway.gate.RedisGateSource;
import com.game.gateway.store.ZoneRow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * gate 排空在真 Redis 上：打标记绑实例、同实例重打不改起点、「最后一台」检查与写在同一段 Lua 里、打标记先清残留 drained、
 * gateway 读目录时只对同实例叠加 draining、判定循环写 drained（TTL 不超过排空标记）、旧实例的标记被清掉、撤销后清残留。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机大号区隔离。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GateDrainIntegrationTest {

    private static RedissonClient redis;
    private final int zone = 900_000 + ThreadLocalRandom.current().nextInt(90_000);
    private NodeDirectory<GateNodeInfo> directory;
    private GateDrainMarks marks;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        redis.shutdown();
    }

    @AfterEach
    void cleanup() {
        for (int node = 1; node <= 2; node++) {
            directory.remove(zone, node);
            marks.clear(zone, node);
        }
    }

    private GateNodeInfo gate(int node, String instance, int players) {
        return GateNodeInfo.newBuilder().setZoneId(zone).setNodeId(node).setInstanceId(instance)
                .setClientHost("10.0.0." + node).setClientPort(11000 + node).setPlayerCount(players).build();
    }

    @Test
    void 打标记_叠加到目录_判定写drained_旧实例标记被清_撤销后清残留() {
        directory = new NodeDirectory<>(redis, NodeTypes.GATE, GateNodeInfo.parser());
        marks = new GateDrainMarks(redis);
        directory.publish(zone, 1, gate(1, "a1", 0), Duration.ofSeconds(30));
        directory.publish(zone, 2, gate(2, "a2", 9), Duration.ofSeconds(30));

        redis.getBucket(RedisKeys.gateDrained(zone, 1), StringCodec.INSTANCE).set("deadline");
        assertThat(marks.mark(zone, 1, "a1", Duration.ofSeconds(60), Map.of(2, "a2"))).isEqualTo(MarkResult.MARKED);
        assertThat(redis.getBucket(RedisKeys.gateDrained(zone, 1)).isExists()).as("打标记先清残留 drained").isFalse();
        GateDrainMarks.Mark mark = marks.draining(zone, List.of(1)).get(1);
        assertThat(mark.markedAtSec()).isBetween(marks.serverTimeSec() - 5, marks.serverTimeSec());
        assertThat(mark.instanceId()).isEqualTo("a1");
        assertThat(marks.mark(zone, 1, "a1", Duration.ofSeconds(600), Map.of(2, "a2"))).isEqualTo(MarkResult.ALREADY_DRAINING);
        assertThat(redis.getKeys().remainTimeToLive(RedisKeys.gateDraining(zone, 1))).as("TTL 不续").isLessThanOrEqualTo(60_000);
        assertThat(marks.mark(zone, 2, "a2", Duration.ofSeconds(60), Map.of(1, "a1")))
                .as("打上之后没有接客的 gate").isEqualTo(MarkResult.LAST_GATE);
        assertThat(marks.draining(zone, List.of(2))).isEmpty();

        List<GateNodeInfo> listed = new RedisGateSource(redis).listGates(zone);
        assertThat(listed).filteredOn(GateNodeInfo::getDraining).extracting(GateNodeInfo::getNodeId).containsExactly(1);

        GateDrainMonitor monitor = new GateDrainMonitor(() -> List.of(new ZoneRow(zone, "z", 0, 100, "", null, true, 1, 0, 0)),
                directory::list, marks, GateDrainSettings.defaults(), marks::serverTimeSec);
        monitor.tick();
        assertThat(marks.drained(zone, List.of(1, 2))).containsOnlyKeys(1).containsEntry(1, "below_threshold");
        long drainedTtl = redis.getKeys().remainTimeToLive(RedisKeys.gateDrained(zone, 1));
        assertThat(drainedTtl).isBetween(1L, redis.getKeys().remainTimeToLive(RedisKeys.gateDraining(zone, 1)) + 50);

        // 节点号 1 被新实例 b1 复用：旧标记不再让它排空，判定循环清掉旧标记
        directory.publish(zone, 1, gate(1, "b1", 0), Duration.ofSeconds(30));
        assertThat(new RedisGateSource(redis).listGates(zone)).noneMatch(GateNodeInfo::getDraining);
        monitor.tick();
        assertThat(marks.draining(zone, List.of(1))).as("旧实例的标记被清掉").isEmpty();
        assertThat(marks.drained(zone, List.of(1))).isEmpty();

        assertThat(marks.mark(zone, 1, "b1", Duration.ofSeconds(60), null)).isEqualTo(MarkResult.MARKED);
        redis.getKeys().delete(RedisKeys.gateDraining(zone, 1));
        monitor.tick();
        assertThat(marks.drained(zone, List.of(1))).as("取消排空后清掉残留 drained").isEmpty();
        redis.getBucket(RedisKeys.gateDraining(zone, 1), StringCodec.INSTANCE).set("1:b1");
        assertThat(marks.mark(zone, 1, "b1", Duration.ofSeconds(60), null)).as("已有标记没有 TTL")
                .isEqualTo(MarkResult.EXISTING_INVALID);
        assertThat(marks.markDrained(zone, 1, marks.draining(zone, List.of(1)).get(1), "below_threshold"))
                .isEqualTo(GateDrainMarks.DrainedWrite.NO_TTL);
        assertThat(marks.markDrained(zone, 1, mark, "below_threshold")).as("标记已不是那一份不写 drained")
                .isEqualTo(GateDrainMarks.DrainedWrite.MARK_CHANGED);
    }
}
