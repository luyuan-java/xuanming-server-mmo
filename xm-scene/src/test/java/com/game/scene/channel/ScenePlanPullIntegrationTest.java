package com.game.scene.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.WorldChannel;
import com.game.discovery.RedisKeys;
import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlanBatch;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.SceneWorld;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 场景节点拉真 Redis 里的频道计划（批次 5.1，scene-channels-spec §9.5 ScenePlanPullIT）：启动只建本节点号名下的 ACTIVE 记录、
 * 登记 zone；版本变化后下一次拉取就应用（新建、排空销毁）；应用后请求补发目录。
 * 默认跳过；显式开启 {@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 9、随机 zone，结束只删本用例的键与 zone 集合里的这一个成员。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class ScenePlanPullIntegrationTest {

    private static final int NODE = 3;
    private static final String TOKEN = "scene-it:" + java.util.UUID.randomUUID();

    private RedissonClient redis;
    private WorldChannelStore store;
    private int zone;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(9);
        redis = Redisson.create(config);
        store = new RedissonWorldChannelStore(redis);
        zone = ThreadLocalRandom.current().nextInt(100_000_000, 2_000_000_000);
        assertThat(store.tryAcquireLeader(zone, TOKEN, Duration.ofSeconds(30))).isTrue();
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().delete(RedisKeys.worldChannels(zone), RedisKeys.worldDesired(zone), RedisKeys.worldCooldown(zone),
                RedisKeys.worldPlanVersion(zone), RedisKeys.worldLeader(zone));
        redis.getSet(RedisKeys.worldZones(), StringCodec.INSTANCE).remove(Integer.toUnsignedString(zone));
        redis.shutdown();
    }

    private static WorldChannel channel(long sceneId, int configId, int nodeId, int slot, ChannelState state) {
        WorldChannel.Builder b = WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(configId).setNodeId(nodeId)
                .setSlot(slot).setState(state).setKind(ChannelKind.CHANNEL_KIND_WORLD);
        if (state == ChannelState.CHANNEL_DRAINING) {
            b.setDrainReason(DrainReason.DRAIN_SCALE_IN);
        }
        return b.build();
    }

    private void write(WorldPlanBatch batch) {
        assertThat(store.write(zone, TOKEN, batch).isWritten()).isTrue();
    }

    @Test
    void 启动只建本节点号名下的ACTIVE记录_版本变化后下一次拉取就应用() {
        long a = 0x8000_0000_0000_0C01L;
        long b = 0x8000_0000_0000_0C02L;
        long c = 0x8000_0000_0000_0C03L;
        long d = 0x8000_0000_0000_0C04L;
        long e = 0x8000_0000_0000_0C05L;
        write(new WorldPlanBatch(0)
                .putChannel(channel(a, 1, NODE, 0, ChannelState.CHANNEL_ACTIVE))
                .putChannel(channel(b, 1, 4, 1, ChannelState.CHANNEL_ACTIVE))        // 别的节点号
                .putChannel(channel(c, 2, NODE, 0, ChannelState.CHANNEL_DRAINING))   // 排空记录：不建
                .putChannel(channel(d, 2, NODE, 1, ChannelState.CHANNEL_ACTIVE)));
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(),
                new FakePlayerRepository(), new AtomicLong(1000)::incrementAndGet, new ManualClock(), SceneMetrics.noop());
        AtomicInteger publishes = new AtomicInteger();
        // 测试线程就是「逻辑线程」：应用直接同步调
        ChannelPlanFollower follower = new ChannelPlanFollower(store, zone, NODE, () -> true,
                world::applyChannelPlan, publishes::incrementAndGet, SceneMetrics.noop());

        assertThat(follower.syncOnce()).isTrue();

        assertThat(world.sceneEntries()).extracting(SceneEntry::getSceneId).containsExactly(a, d);
        assertThat(world.appliedPlanVersion()).isEqualTo(1);
        assertThat(store.zones()).contains(zone);
        assertThat(publishes).hasValue(1);

        write(new WorldPlanBatch(1)
                .putChannel(channel(a, 1, NODE, 0, ChannelState.CHANNEL_DRAINING))
                .putChannel(channel(e, 1, NODE, 1, ChannelState.CHANNEL_ACTIVE)));
        follower.pollOnce();

        assertThat(world.sceneEntries()).as("空的排空频道应用后立即销毁").extracting(SceneEntry::getSceneId)
                .containsExactly(d, e);
        assertThat(world.appliedPlanVersion()).isEqualTo(2);
        assertThat(publishes).hasValue(2);

        follower.pollOnce();
        assertThat(publishes).as("版本不变：不重应用、不补发").hasValue(2);
    }
}
