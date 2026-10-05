package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.WorldChannel;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldChannels;
import com.game.discovery.world.WorldPlan;
import com.game.scenemanager.ChannelSelector;
import com.game.scenemanager.RedisSceneNodeSource;
import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.SceneIdAllocator;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.SwitchTargetSelector;
import com.game.scenemanager.WorldSceneConfigs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 控制面连真 Redis（scene-channels-spec §9.5 WorldLeaderFailoverIT 与围栏写入）：两个协调者竞选、只有一个写、频道数不翻倍；
 * 领导者停止续期后跟随者在 TTL 内接管；锁被夺后旧领导者的写入被围栏（−1）并降级；版本冲突（−2）本拍作废、下拍重算；
 * 分配的软预占把并发进场摊开；换图选目标（5.2）与进场共用同一份预占。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 9，每个用例一个随机 zone，结束时只删自己的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class WorldChannelRedisIntegrationTest {

    private static final List<Integer> CONFS = List.of(1, 2);

    private static RedissonClient redis;
    private static WorldChannelStore store;
    private static final List<Integer> ZONES = new ArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 50));

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(9);
        redis = Redisson.create(config);
        store = new RedissonWorldChannelStore(redis);
    }

    @AfterAll
    static void cleanup() {
        for (int zone : ZONES) {
            redis.getKeys().deleteByPattern("xm:world:{z:" + Integer.toUnsignedString(zone) + "}:*");
            redis.getSet(RedisKeys.worldZones(), StringCodec.INSTANCE).remove(Integer.toUnsignedString(zone));
        }
        redis.shutdown();
    }

    private static synchronized int newZone() {
        int zone = ThreadLocalRandom.current().nextInt(100_000_000, 2_000_000_000);
        ZONES.add(zone);
        store.registerZone(zone);
        return zone;
    }

    /** 一个 zone 的假目录：节点号 → 条目（承载计划里属于它的 ACTIVE 记录、已应用当前版本）。 */
    private static final class Directory implements SceneNodeSource {
        final int zone;
        final Map<Integer, SceneNodeInfo> nodes = new TreeMap<>();
        Runnable onList = () -> { };

        Directory(int zone) {
            this.zone = zone;
        }

        void host(int nodeId) {
            WorldPlan plan = store.readPlan(zone);
            SceneNodeInfo.Builder b = SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(nodeId).setInstanceId("it-" + nodeId)
                    .setLinkHost("127.0.0.1").setLinkPort(21000 + nodeId).setAppliedPlanVersion(plan.version());
            for (WorldChannel c : plan.channelsOn(nodeId)) {
                if (c.getState() == ChannelState.CHANNEL_ACTIVE) {
                    b.addScenes(SceneEntry.newBuilder().setSceneId(c.getSceneId()).setSceneConfigId(c.getSceneConfigId()));
                }
            }
            nodes.put(nodeId, b.build());
        }

        @Override
        public List<SceneNodeInfo> list(int zoneId) {
            onList.run();
            return zoneId == zone ? new ArrayList<>(nodes.values()) : List.of();
        }
    }

    private static WorldChannelCoordinator coordinator(Directory directory, String token, Duration lockTtl,
                                                       SimpleMeterRegistry meters) {
        WorldChannelProperties props = PlanFixture.props(Map.of("leader-lock-ttl", lockTtl.toMillis() + "ms"));
        WorldChannelPlanner planner = WorldChannelPlanner.fromDirectory(props, CONFS,
                () -> OptionalLong.of(IDS.incrementAndGet()));
        return new WorldChannelCoordinator(scoped(directory.zone), directory, planner, props, new WorldChannelMetrics(meters, CONFS),
                NodeAvailability.ALL, token, System::nanoTime);
    }

    /**
     * 只看见本用例 zone 的存储：DB 9 的 {@code xm:world:zones} 是共享的（别的用例 / 别的模块的测试也往里登记），协调者竞选时只碰自己的 zone。
     */
    private static WorldChannelStore scoped(int zone) {
        return (WorldChannelStore) Proxy.newProxyInstance(WorldChannelStore.class.getClassLoader(),
                new Class<?>[] {WorldChannelStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("zones")) {
                        return List.of(zone);
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static double ticks(SimpleMeterRegistry meters, String result) {
        return meters.get(WorldChannelMetrics.TICKS).tag("result", result).counter().count();
    }

    @Test
    void 两个协调者竞选_只有一个写_轮流跑频道数不翻倍_ver单调() {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        dir.host(20);
        SimpleMeterRegistry ma = new SimpleMeterRegistry();
        SimpleMeterRegistry mb = new SimpleMeterRegistry();
        WorldChannelCoordinator a = coordinator(dir, "it-a:" + zone, Duration.ofSeconds(30), ma);
        WorldChannelCoordinator b = coordinator(dir, "it-b:" + zone, Duration.ofSeconds(30), mb);

        long lastVersion = 0;
        java.util.Set<Long> versions = new java.util.TreeSet<>();
        for (int i = 0; i < 4; i++) {
            a.tick();
            b.tick();
            dir.host(10);
            dir.host(20);
            long v = store.planVersion(zone);
            assertThat(v).isGreaterThanOrEqualTo(lastVersion);
            lastVersion = v;
            versions.add(v);
        }

        assertThat(store.readPlan(zone).channels()).hasSize(4); // 2 节点 × 2 图（per-node 覆盖）
        // 只写过一次：版本号从 0 跳到以 Redis TIME 托底的毫秒值后不再动
        assertThat(versions).hasSize(1);
        assertThat(lastVersion).isGreaterThan(1_000_000_000_000L);
        assertThat(a.ledZones()).containsExactly(zone);
        assertThat(b.ledZones()).isEmpty();
        assertThat(ticks(mb, "not_leader")).isEqualTo(4);
        a.releaseAll();
        b.releaseAll();
    }

    @Test
    void 领导者停止续期后_跟随者在TTL内接管_旧领导者续期即降级() throws Exception {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        SimpleMeterRegistry ma = new SimpleMeterRegistry();
        SimpleMeterRegistry mb = new SimpleMeterRegistry();
        Duration ttl = Duration.ofSeconds(3);
        WorldChannelCoordinator a = coordinator(dir, "it-a:" + zone, ttl, ma);
        WorldChannelCoordinator b = coordinator(dir, "it-b:" + zone, ttl, mb);
        a.tick();
        long versionByA = store.planVersion(zone);
        assertThat(versionByA).isGreaterThan(1_000_000_000_000L);
        dir.host(10);
        b.tick();
        assertThat(b.ledZones()).isEmpty();

        // a 不再续期（进程卡死）：锁在 TTL 后过期，b 在下一拍接管
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (b.ledZones().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            b.tick();
        }
        assertThat(b.ledZones()).containsExactly(zone);
        assertThat(redis.<String>getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).get()).isEqualTo("it-b:" + zone);
        // 接管后计划不变（b 读到的就是 a 写的）
        assertThat(store.readPlan(zone).channels()).hasSize(2);
        assertThat(store.planVersion(zone)).isEqualTo(versionByA);

        // a 醒来：本地有效期早过了，不写；续期发现锁已不是自己的，降级
        a.tick();
        assertThat(ticks(ma, "not_leader")).isEqualTo(1);
        a.renewLeaders();
        assertThat(a.ledZones()).isEmpty();
        a.releaseAll();
        assertThat(redis.<String>getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).get()).isEqualTo("it-b:" + zone);
        b.releaseAll();
    }

    @Test
    void 锁被夺后旧领导者的写入被围栏并降级() {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        SimpleMeterRegistry ma = new SimpleMeterRegistry();
        WorldChannelCoordinator a = coordinator(dir, "it-a:" + zone, Duration.ofSeconds(30), ma);
        a.tick();
        dir.host(10);
        long version = store.planVersion(zone);

        // 双领导窗口：锁值已是别人的，但 a 本地仍认为有效；新节点出现，a 要写
        redis.<String>getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).set("it-x:" + zone, Duration.ofSeconds(30));
        dir.host(20);
        a.tick();

        assertThat(ticks(ma, "fenced")).isEqualTo(1);
        assertThat(a.ledZones()).isEmpty();
        assertThat(store.planVersion(zone)).isEqualTo(version);
        assertThat(store.readPlan(zone).channelsOn(20)).isEmpty();
        redis.getBucket(RedisKeys.worldLeader(zone)).delete();
    }

    @Test
    void 版本冲突_本拍作废_下拍重读重算() {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        SimpleMeterRegistry ma = new SimpleMeterRegistry();
        WorldChannelCoordinator a = coordinator(dir, "it-a:" + zone, Duration.ofSeconds(30), ma);
        // 快照之后、写入之前（目录读取发生在两者之间）有人改了版本号（运维手改 / 双领导）
        AtomicBoolean bumped = new AtomicBoolean();
        dir.onList = () -> {
            if (bumped.compareAndSet(false, true)) {
                redis.getAtomicLong(RedisKeys.worldPlanVersion(zone)).incrementAndGet();
            }
        };

        a.tick();
        assertThat(ticks(ma, "conflict")).isEqualTo(1);
        assertThat(store.readPlan(zone).channels()).isEmpty();
        assertThat(store.planVersion(zone)).isEqualTo(1);

        a.tick();
        assertThat(ticks(ma, "ok")).isEqualTo(1);
        assertThat(store.readPlan(zone).channels()).hasSize(2);
        // 重读到 1 后写入：新版本以 Redis TIME 托底
        assertThat(store.planVersion(zone)).isGreaterThan(1_000_000_000_000L);
        a.releaseAll();
    }

    @Test
    void 控制面起停_占发号租约_线程自己跑拍铺好计划_停服放锁还租约() throws Exception {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        WorldChannelProperties props = PlanFixture.props(Map.of("tick", "200ms", "leader-lock-ttl", "3s"));
        // 发号租约由独立的 SceneIdAllocator 占（批次 5.3 R5），控制面只借用；停服顺序同 Spring：先停控制面，再还租约
        SceneIdAllocator ids = SceneIdAllocator.acquire(redis);
        WorldChannelControlPlane plane = WorldChannelControlPlane.start(scoped(zone), dir,
                new WorldSceneConfigs(1, new LinkedHashSet<>(CONFS)), props,
                new WorldChannelMetrics(new SimpleMeterRegistry(), CONFS), ids);
        int worker = plane.idWorker();
        try {
            assertThat(worker).isEqualTo(ids.worker());
            assertThat(worker).isBetween(1, 1023);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (store.readPlan(zone).channels().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(store.readPlan(zone).channels()).hasSize(2).allSatisfy(c -> {
                assertThat(c.getSceneId()).isPositive();
                assertThat(c.getNodeId()).isEqualTo(10);
            });
            assertThat(redis.<String>getBucket(RedisKeys.worldLeader(zone), StringCodec.INSTANCE).get())
                    .isEqualTo(plane.coordinator().token());
            assertThat(redis.getBucket(RedisKeys.nodeId(WorldChannels.ID_LEASE_NODE_TYPE, 0, worker)).isExists()).isTrue();
        } finally {
            plane.close();
        }
        assertThat(redis.getBucket(RedisKeys.worldLeader(zone)).isExists()).isFalse();
        assertThat(redis.getBucket(RedisKeys.nodeId(WorldChannels.ID_LEASE_NODE_TYPE, 0, worker)).isExists())
                .as("控制面停了，租约仍归发号器（实例取号还在用）").isTrue();
        ids.close();
        assertThat(redis.getBucket(RedisKeys.nodeId(WorldChannels.ID_LEASE_NODE_TYPE, 0, worker)).isExists()).isFalse();
        redis.getBucket(RedisKeys.nodeIdEpoch(WorldChannels.ID_LEASE_NODE_TYPE, 0, worker)).delete();
    }

    @Test
    void 节点经Redis目录上报镜像后_种类与源不丢_领导者一拍内缩容跳过镜像源_login不分进镜像() {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        // 种子：每张图在节点 10 上 2 个频道
        WorldChannelProperties seedProps = PlanFixture.props(Map.of("channel-count", "2"));
        WorldChannelCoordinator seeder = new WorldChannelCoordinator(scoped(zone), dir,
                WorldChannelPlanner.fromDirectory(seedProps, CONFS, () -> OptionalLong.of(IDS.incrementAndGet())),
                seedProps, new WorldChannelMetrics(new SimpleMeterRegistry(), CONFS), NodeAvailability.ALL,
                "it-seed:" + zone, System::nanoTime);
        seeder.tick();
        seeder.releaseAll();
        WorldPlan plan = store.readPlan(zone);
        List<Long> conf1 = plan.channelsOn(10).stream().filter(c -> c.getSceneConfigId() == 1)
                .map(WorldChannel::getSceneId).sorted(Long::compareUnsigned).toList();
        assertThat(conf1).hasSize(2);
        long mirrorSource = conf1.get(0);   // 人数并列时缩容先选它（场景号小），挂上镜像后必须跳过
        long other = conf1.get(1);
        long mirrorId = IDS.incrementAndGet();

        // scene 节点按 xm-scene 的方式经 Redis 节点目录上报：计划里的频道（WORLD）+ 一个指向 mirrorSource 的镜像（0 人，比任何频道都「闲」）
        SceneNodeInfo.Builder info = SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(10).setInstanceId("it-10")
                .setLinkHost("127.0.0.1").setLinkPort(21010).setAppliedPlanVersion(plan.version());
        for (WorldChannel c : plan.channelsOn(10)) {
            info.addScenes(SceneEntry.newBuilder().setSceneId(c.getSceneId()).setSceneConfigId(c.getSceneConfigId())
                    .setPlayerCount(5).setKind(ChannelKind.CHANNEL_KIND_WORLD));
        }
        info.addScenes(SceneEntry.newBuilder().setSceneId(mirrorId).setSceneConfigId(1)
                .setKind(ChannelKind.CHANNEL_KIND_MIRROR).setSourceSceneId(mirrorSource));
        NodeDirectory<SceneNodeInfo> published = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        published.publish(zone, 10, info.build(), Duration.ofSeconds(15));
        try {
            RedisSceneNodeSource source = new RedisSceneNodeSource(redis);
            DirectoryView view = DirectoryView.of(zone, source.list(zone));
            DirectoryView.Scene mirror = view.node(10).scenes().get(mirrorId);
            assertThat(mirror.kind()).isEqualTo(ChannelKind.CHANNEL_KIND_MIRROR);
            assertThat(mirror.sourceSceneId()).isEqualTo(mirrorSource);
            assertThat(DirectoryMirrorSources.of(view).isSource(mirrorSource)).isTrue();
            assertThat(DirectoryMirrorSources.of(view).isSource(other)).isFalse();

            // login 进游戏只认主世界频道（R1）：镜像 0 人也不会被选，预占也不落在它上面
            SceneAssigner assigner = new SceneAssigner(source, new WorldSceneConfigs(1, new LinkedHashSet<>(CONFS)),
                    new ChannelSelector(source, store, Duration.ofSeconds(10)));
            for (long p = 1; p <= 4; p++) {
                assertThat(assigner.assign(AssignSceneRequest.newBuilder().setZoneId(zone).setPlayerId(8_000_000L + p)
                        .build()).getSceneId()).isIn(mirrorSource, other);
            }
            assertThat(store.countReservations(zone, List.of(mirrorId))).containsExactly(0L);

            // 新领导者开着自动扩缩容（缩容线 100，人数都是 5）：一拍内缩掉的是 other，镜像源不动
            WorldChannelProperties props = PlanFixture.props(Map.of("channel-count", "2", "autoscale.enabled", "true"));
            WorldChannelCoordinator leader = new WorldChannelCoordinator(scoped(zone), source,
                    WorldChannelPlanner.fromDirectory(props, CONFS, () -> OptionalLong.of(IDS.incrementAndGet())),
                    props, new WorldChannelMetrics(new SimpleMeterRegistry(), CONFS), NodeAvailability.ALL,
                    "it-as:" + zone, System::nanoTime);
            leader.tick();
            leader.releaseAll();
            Map<Long, WorldChannel> after = new HashMap<>();
            for (WorldChannel c : store.readPlan(zone).channels()) {
                after.put(c.getSceneId(), c);
            }
            assertThat(after.get(mirrorSource).getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
            assertThat(after.get(other).getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
            assertThat(after).as("镜像不进计划").doesNotContainKey(mirrorId);
        } finally {
            published.remove(zone, 10);
        }
    }

    @Test
    void 分配的软预占把并发进场摊开() throws Exception {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        dir.host(20);
        // 种子 4：每张图在两个节点上各 2 个频道（per-node 覆盖 + 补足到 4）
        WorldChannelProperties props = PlanFixture.props(Map.of("channel-count", "4"));
        WorldChannelCoordinator seeded = new WorldChannelCoordinator(scoped(zone), dir,
                WorldChannelPlanner.fromDirectory(props, CONFS, () -> OptionalLong.of(IDS.incrementAndGet())),
                props, new WorldChannelMetrics(new SimpleMeterRegistry(), CONFS), NodeAvailability.ALL, "it-a:" + zone,
                System::nanoTime);
        seeded.tick();
        dir.host(10);
        dir.host(20);
        seeded.releaseAll();
        assertThat(store.readPlan(zone).channels()).filteredOn(c -> c.getSceneConfigId() == 1).hasSize(4);

        WorldSceneConfigs world = new WorldSceneConfigs(1, new LinkedHashSet<>(CONFS));
        SceneAssigner assigner = new SceneAssigner(dir, world, new ChannelSelector(dir, store, Duration.ofSeconds(10)));
        int players = 40;
        List<Thread> threads = new ArrayList<>();
        Map<Long, Integer> spread = new HashMap<>();
        for (int i = 0; i < players; i++) {
            long playerId = 7_000_000L + i;
            Thread t = Thread.ofVirtual().start(() -> {
                long scene = assigner.assign(AssignSceneRequest.newBuilder().setZoneId(zone).setPlayerId(playerId).build())
                        .getSceneId();
                synchronized (spread) {
                    spread.merge(scene, 1, Integer::sum);
                }
            });
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(spread).hasSize(4).allSatisfy((scene, n) -> assertThat(n).isBetween(9, 11));
        List<Long> counts = store.countReservations(zone, new ArrayList<>(spread.keySet()));
        assertThat(counts.stream().mapToLong(Long::longValue).sum()).isEqualTo(players);
    }

    /** 批次 5.2（scene-handoff-spec §5.4）：换图选目标与进场共用同一份软预占；只带地图排除源场景，重试不重复计数，显式场景号也写预占。 */
    @Test
    void 换图选目标排除源场景并写真预占() {
        int zone = newZone();
        Directory dir = new Directory(zone);
        dir.host(10);
        dir.host(20);
        WorldChannelCoordinator seeded = coordinator(dir, "it-switch:" + zone, Duration.ofSeconds(30), new SimpleMeterRegistry());
        seeded.tick();
        dir.host(10);
        dir.host(20);
        seeded.releaseAll();
        WorldPlan plan = store.readPlan(zone);
        long fromScene = plan.channelsOn(10).stream().filter(c -> c.getSceneConfigId() == 1).findFirst().orElseThrow().getSceneId();
        long otherScene = plan.channelsOn(20).stream().filter(c -> c.getSceneConfigId() == 1).findFirst().orElseThrow().getSceneId();
        long explicitScene = plan.channelsOn(10).stream().filter(c -> c.getSceneConfigId() == 2).findFirst().orElseThrow().getSceneId();

        WorldSceneConfigs world = new WorldSceneConfigs(1, new LinkedHashSet<>(CONFS));
        SwitchTargetSelector selector = new SwitchTargetSelector(dir, world, new ChannelSelector(dir, store, Duration.ofSeconds(10)));
        SelectSwitchTargetRequest byMap = SelectSwitchTargetRequest.newBuilder().setZoneId(zone).setPlayerId(8_000_001L)
                .setFromSceneNodeId(10).setFromSceneId(fromScene).setWantSceneConfigId(1).build();

        for (int attempt = 0; attempt < 2; attempt++) {
            SwitchTargetSelector.Selection s = selector.select(byMap);
            assertThat(s.result()).isEqualTo(SwitchTargetSelector.Result.OK);
            assertThat(s.response().getSceneNodeId()).isEqualTo(20);
            assertThat(s.response().getSceneId()).isEqualTo(otherScene);
        }
        assertThat(store.countReservations(zone, List.of(fromScene, otherScene))).containsExactly(0L, 1L);

        SwitchTargetSelector.Selection explicit = selector.select(SelectSwitchTargetRequest.newBuilder().setZoneId(zone)
                .setPlayerId(8_000_002L).setFromSceneNodeId(20).setFromSceneId(otherScene).setWantSceneId(explicitScene).build());
        assertThat(explicit.result()).isEqualTo(SwitchTargetSelector.Result.OK);
        assertThat(explicit.response().getSceneNodeId()).isEqualTo(10);
        assertThat(explicit.response().getSceneConfigId()).isEqualTo(2);
        assertThat(store.countReservations(zone, List.of(explicitScene))).containsExactly(1L);
    }
}
