package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BattleNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.match.gather.BattleNodes.Census;
import com.game.match.gather.BattleNodes.Lookup;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * battle 目录的读口连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）：经生产用的 {@link NodeDirectory}
 * （battle 节点自己发布条目用的同一个类）读 {@code xm:nodes:battle:0}——选节点、概况、按节点号比对实例，以及在虚拟线程上调用。
 * 用 DB 13；目录键是全局的，所以只发布随机的大节点号、用完只摘自己发布的，断言也只针对自己的条目（别人同时发布的条目不影响结论）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisBattleNodesIntegrationTest {

    private static RedissonClient redis;
    private static NodeDirectory<BattleNodeInfo> directory;
    private final Set<Integer> published = new HashSet<>();
    private final MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));

    @BeforeAll
    static void open() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        redis = Redisson.create(config);
        directory = new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser());
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @AfterEach
    void removeMine() {
        published.forEach(nodeId -> directory.remove(RedisBattleNodes.BATTLE_SCOPE, nodeId));
    }

    /** 发布一条随机大节点号的条目（TTL 30 s，测试结束即摘）。 */
    private BattleNodeInfo publish(String instanceId, boolean accepting, int rpcPort) {
        int nodeId = ThreadLocalRandom.current().nextInt(1 << 28, 1 << 30);
        BattleNodeInfo info = BattleNodeInfo.newBuilder().setNodeId(nodeId).setInstanceId(instanceId).setRpcHost("10.9.9.9").setRpcPort(rpcPort)
                .setAccepting(accepting).build();
        directory.publish(RedisBattleNodes.BATTLE_SCOPE, nodeId, info, Duration.ofSeconds(30));
        published.add(nodeId);
        return info;
    }

    @Test
    void 按节点号比对实例_同实例_换了实例_摘掉之后不在册() {
        RedisBattleNodes nodes = new RedisBattleNodes(directory, metrics);
        BattleNodeInfo mine = publish("inst-it-a", true, 21200);

        assertThat(nodes.lookup(mine.getNodeId(), "inst-it-a")).isEqualTo(Lookup.SAME_INSTANCE);
        assertThat(nodes.lookup(mine.getNodeId(), "inst-it-old")).isEqualTo(Lookup.OTHER_INSTANCE);

        directory.remove(RedisBattleNodes.BATTLE_SCOPE, mine.getNodeId());
        assertThat(nodes.lookup(mine.getNodeId(), "inst-it-a")).isEqualTo(Lookup.ABSENT);
    }

    @Test
    void 概况数得到自己发布的条目_选出来的一定是可分配的_排除自己之后不会再选到() {
        RedisBattleNodes nodes = new RedisBattleNodes(directory, metrics);
        Census before = nodes.census();
        BattleNodeInfo open = publish("inst-it-open", true, 21200);
        BattleNodeInfo closed = publish("inst-it-closed", false, 21201);
        BattleNodeInfo noPort = publish("inst-it-noport", true, 0);

        Census after = nodes.census();
        assertThat(after.readFailed()).isFalse();
        assertThat(after.accepting()).as("多了一条可分配的").isGreaterThanOrEqualTo(before.accepting() + 1);
        assertThat(after.notAccepting()).as("多了两条不可分配的（关闸、没导出控制面）").isGreaterThanOrEqualTo(before.notAccepting() + 2);
        assertThat(after.nothingAllocatable()).isFalse();

        for (int i = 0; i < 40; i++) {
            BattleNodeInfo picked = nodes.pickRandom(Set.of()).orElseThrow();
            assertThat(RedisBattleNodes.allocatable(picked)).isTrue();
            assertThat(picked.getNodeId()).isNotIn(closed.getNodeId(), noPort.getNodeId());

            Optional<BattleNodeInfo> withoutMine = nodes.pickRandom(Set.of(BattleNodes.key(open)));
            assertThat(withoutMine.map(BattleNodeInfo::getNodeId)).as("按 (节点号, 实例) 排除").isNotEqualTo(Optional.of(open.getNodeId()));
        }
    }

    @Test
    void 在虚拟线程上读目录_同gather的用法() throws Exception {
        RedisBattleNodes nodes = new RedisBattleNodes(directory, metrics);
        BattleNodeInfo mine = publish("inst-it-vt", true, 21200);
        AtomicReference<Object> outcome = new AtomicReference<>();

        Thread thread = Thread.ofVirtual().name("test-battle-nodes").start(() -> {
            try {
                boolean picked = nodes.pickRandom(Set.of()).isPresent();
                outcome.set(picked + ":" + nodes.census().readFailed() + ":" + nodes.lookup(mine.getNodeId(), "inst-it-vt"));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });

        assertThat(thread.join(Duration.ofSeconds(30))).isTrue();
        assertThat(outcome.get()).isEqualTo("true:false:SAME_INSTANCE");
    }
}
