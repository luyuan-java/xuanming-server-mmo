package com.game.discovery.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import com.game.common.GameNamespace;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.drain.GateDrainMarks;
import com.game.discovery.drain.GateDrainMarks.MarkResult;
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
import org.redisson.config.Config;

/**
 * gate 目录读取面 + {@link GatePicker} 在真 Redis 上（批次 5.4，zone-travel-spec §11.8）：game-gate 的写法发布目录条目，
 * {@link RedisGateSource} 读出来（同实例的排空标记叠加成 draining），{@link GatePicker} 同一客户端地址只认最新启动的条目、剔排空。
 * 默认跳过；显式开启：{@code -Dgame.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机大号区隔离，结束时只删自己写的键。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class RedisGateSourceIntegrationTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private static RedissonClient redis;
    private final int zone = 900_000 + ThreadLocalRandom.current().nextInt(90_000);
    private NodeDirectory<GateNodeInfo> directory;
    private GateDrainMarks marks;

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

    @AfterEach
    void cleanup() {
        for (int node = 1; node <= 3; node++) {
            directory.remove(zone, node);
            marks.clear(zone, node);
        }
    }

    private GateNodeInfo gate(int node, String host, int port, int players, long startedAtMs) {
        return GateNodeInfo.newBuilder().setZoneId(zone).setNodeId(node).setInstanceId("it-" + node)
                .setClientHost(host).setClientPort(port).setPlayerCount(players).setStartedAtMs(startedAtMs).build();
    }

    @Test
    void 同地址只认最新启动的条目_排空标记照旧叠加() {
        directory = new NodeDirectory<>(redis, REDIS_KEYS, NodeTypes.GATE, GateNodeInfo.parser());
        marks = new GateDrainMarks(redis, REDIS_KEYS);
        // 节点 1：崩溃前的旧进程（人数最少、条目还没过期）；节点 2：同一地址重启后的新进程；节点 3：另一台
        directory.publish(zone, 1, gate(1, "10.9.0.1", 11000, 0, 1_000L), Duration.ofSeconds(30));
        directory.publish(zone, 2, gate(2, "10.9.0.1", 11000, 40, 2_000L), Duration.ofSeconds(30));
        directory.publish(zone, 3, gate(3, "10.9.0.3", 11000, 60, 500L), Duration.ofSeconds(30));
        RedisGateSource source = new RedisGateSource(redis, REDIS_KEYS);

        List<GateNodeInfo> listed = source.listGates(zone);
        assertThat(listed).extracting(GateNodeInfo::getNodeId).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(listed).filteredOn(g -> g.getNodeId() == 2).extracting(GateNodeInfo::getStartedAtMs)
                .as("started_at_ms 原样过目录").containsExactly(2_000L);
        assertThat(GatePicker.pick(zone, listed).orElseThrow().getNodeId()).as("陈旧条目人数更少也不选").isEqualTo(2);
        assertThat(GatePicker.eligible(zone, listed)).extracting(GateNodeInfo::getNodeId).containsExactlyInAnyOrder(2, 3);

        // 新进程被运维标排空：剔掉它之后只剩节点 3，陈旧条目不复活
        assertThat(marks.mark(zone, 2, "it-2", Duration.ofSeconds(60), Map.of(3, "it-3"))).isEqualTo(MarkResult.MARKED);
        List<GateNodeInfo> withMark = source.listGates(zone);
        assertThat(withMark).filteredOn(GateNodeInfo::getDraining).extracting(GateNodeInfo::getNodeId).containsExactly(2);
        assertThat(GatePicker.pick(zone, withMark).orElseThrow().getNodeId()).isEqualTo(3);
    }
}
