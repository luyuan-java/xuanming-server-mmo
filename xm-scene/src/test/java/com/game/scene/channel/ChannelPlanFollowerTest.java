package com.game.scene.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.game.api.proto.ChannelState;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.ReservationCandidate;
import com.game.discovery.world.ReservationPick;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.discovery.world.WorldPlanBatch;
import com.game.discovery.world.WorldPlanSnapshot;
import com.game.discovery.world.WorldPlanWriteResult;
import com.game.scene.metrics.SceneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 频道计划拉取（批次 5.1，scene-channels-spec §4.10.1、§9.4 ChannelPlanFollowerTest）：版本不变不整读；租约无效跳过；
 * 读失败 / 应用失败什么也不应用、下次重试；只交本节点号名下的记录；应用成功后请求立即补发目录；登记 zone 失败不挡拉取。
 * 用假的 {@link WorldChannelStore}（只实现拉取用到的几个方法）。
 */
class ChannelPlanFollowerTest {

    private static final int ZONE = 7;
    private static final int NODE = 3;

    /** 拉取者只用到版本号、整读、登记 zone；别的方法调到就是错。 */
    private static final class FakeStore implements WorldChannelStore {
        long version;
        List<WorldChannel> channels = List.of();
        RuntimeException failure;
        RuntimeException registerFailure;
        int versionReads;
        int planReads;
        final List<Integer> registered = new ArrayList<>();

        @Override
        public long planVersion(int zoneId) {
            assertThat(zoneId).isEqualTo(ZONE);
            versionReads++;
            if (failure != null) {
                throw failure;
            }
            return version;
        }

        @Override
        public WorldPlan readPlan(int zoneId) {
            assertThat(zoneId).isEqualTo(ZONE);
            planReads++;
            if (failure != null) {
                throw failure;
            }
            return new WorldPlan(version, channels);
        }

        @Override
        public void registerZone(int zoneId) {
            if (registerFailure != null) {
                throw registerFailure;
            }
            registered.add(zoneId);
        }

        @Override
        public WorldPlanSnapshot snapshot(int zoneId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorldPlanWriteResult write(int zoneId, String leaderToken, WorldPlanBatch batch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryAcquireLeader(int zoneId, String token, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean renewLeader(int zoneId, String token, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean releaseLeader(int zoneId, String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReservationPick reserve(int zoneId, List<ReservationCandidate> candidates, long playerId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reserveScene(int zoneId, long sceneId, long playerId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean releaseReservation(int zoneId, long sceneId, long playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> countReservations(int zoneId, List<Long> sceneIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Integer> zones() {
            throw new UnsupportedOperationException();
        }
    }

    private record Applied(long version, List<WorldChannel> mine) {
    }

    private final FakeStore store = new FakeStore();
    private final List<Applied> applied = new ArrayList<>();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final AtomicInteger publishes = new AtomicInteger();
    private Exception applyFailure;
    private SimpleMeterRegistry meters;
    private ChannelPlanFollower follower;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        follower = new ChannelPlanFollower(store, ZONE, NODE, leaseValid::get, (version, mine) -> {
            if (applyFailure != null) {
                throw applyFailure;
            }
            applied.add(new Applied(version, mine));
        }, publishes::incrementAndGet, new SceneMetrics(meters));
    }

    private static WorldChannel channel(long sceneId, int nodeId) {
        return WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(1).setNodeId(nodeId)
                .setState(ChannelState.CHANNEL_ACTIVE).build();
    }

    @Test
    void 启动同步一次_登记zone_只交本节点号名下的记录_应用后请求补发目录() {
        store.version = 4;
        store.channels = List.of(channel(0x8000_0000_0000_0001L, NODE), channel(2, 9), channel(3, NODE));

        assertThat(follower.syncOnce()).isTrue();

        assertThat(store.registered).containsExactly(ZONE);
        assertThat(applied).containsExactly(new Applied(4,
                List.of(channel(0x8000_0000_0000_0001L, NODE), channel(3, NODE))));
        assertThat(follower.appliedVersion()).isEqualTo(4);
        assertThat(publishes).hasValue(1);
        assertThat(counter("xm.scene.channel.plan.applies", "skipped_lease")).isZero();
    }

    @Test
    void Redis里还没有计划_版本0也整读一次_之后版本不变就不整读() {
        assertThat(follower.syncOnce()).isTrue();
        assertThat(applied).containsExactly(new Applied(0, List.of()));

        follower.pollOnce();
        follower.pollOnce();

        assertThat(store.versionReads).isEqualTo(3);
        assertThat(store.planReads).as("版本不变只读版本号").isEqualTo(1);
        assertThat(applied).hasSize(1);
        assertThat(store.registered).as("登记成功后不再登记").containsExactly(ZONE);

        store.version = 1;
        store.channels = List.of(channel(5, NODE));
        follower.pollOnce();
        assertThat(applied).last().isEqualTo(new Applied(1, List.of(channel(5, NODE))));
        assertThat(publishes).hasValue(2);
    }

    @Test
    void 租约无效_跳过不读不应用_计skipped_lease_恢复后照常() {
        leaseValid.set(false);
        store.version = 2;

        assertThat(follower.syncOnce()).isFalse();
        follower.pollOnce();

        assertThat(store.versionReads).isZero();
        assertThat(applied).isEmpty();
        assertThat(counter("xm.scene.channel.plan.applies", "skipped_lease")).isEqualTo(2);

        leaseValid.set(true);
        follower.pollOnce();
        assertThat(applied).extracting(Applied::version).containsExactly(2L);
    }

    @Test
    void 读失败_什么也不应用_计失败_恢复后应用() {
        store.version = 3;
        store.failure = new IllegalStateException("Redis 断开");

        assertThat(follower.syncOnce()).isFalse();
        follower.pollOnce();

        assertThat(applied).as("读失败不能把本地场景当孤儿：什么也不交").isEmpty();
        assertThat(follower.appliedVersion()).isEqualTo(-1);
        assertThat(meters.get("xm.scene.channel.plan.poll.failures").counter().count()).isEqualTo(2);
        assertThat(publishes).hasValue(0);

        store.failure = null;
        follower.pollOnce();
        assertThat(applied).extracting(Applied::version).containsExactly(3L);
    }

    @Test
    void 逻辑线程没及时应用_不记版本_下次重投同一版本() {
        store.version = 6;
        applyFailure = new TimeoutException("逻辑线程忙");

        follower.pollOnce();
        assertThat(follower.appliedVersion()).isEqualTo(-1);
        assertThat(publishes).hasValue(0);

        applyFailure = null;
        follower.pollOnce();
        assertThat(store.planReads).isEqualTo(2);
        assertThat(applied).extracting(Applied::version).containsExactly(6L);
        assertThat(follower.appliedVersion()).isEqualTo(6);
    }

    @Test
    void 登记zone失败_照样拉计划_下一周期再登记() {
        store.registerFailure = new IllegalStateException("Redis 抖动");
        store.version = 1;

        assertThat(follower.syncOnce()).isTrue();
        assertThat(applied).hasSize(1);
        assertThat(store.registered).isEmpty();

        store.registerFailure = null;
        follower.pollOnce();
        assertThat(store.registered).containsExactly(ZONE);
    }

    @Test
    void 周期拉取_停止后不再拉() throws Exception {
        store.version = 9;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            follower.start(scheduler, Duration.ofMillis(100));
            await().atMost(Duration.ofSeconds(5)).until(() -> follower.appliedVersion() == 9);
            follower.stop();
        } finally {
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        int reads = store.versionReads;

        follower.pollOnce();

        assertThat(store.versionReads).as("停止后的拉取直接返回").isEqualTo(reads);
    }

    private double counter(String name, String result) {
        return meters.get(name).tag("result", result).counter().count();
    }
}
