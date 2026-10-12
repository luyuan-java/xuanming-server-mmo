package com.game.discovery.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 定位的判定顺序（照基线 {@code locator_test.go} 改成 Java 的状态，guild-economy-spec §11.2「SceneAssetLocatorTest」）：
 * o / l / x / 缺失 / 损坏 / 键不符 / Redis 错 / 目录缺节点 / rpc_port = 0 / 同号新实例。假位置源与假目录，不连 Redis。
 */
class SceneAssetLocatorTest {

    private static final long PLAYER = Long.MIN_VALUE + 42;
    private static final int ZONE = 3;
    private static final int NODE = 7;

    private final Map<Long, CompletableFuture<HolderRead>> holders = new HashMap<>();
    private final Map<Integer, CompletableFuture<Optional<SceneNodeInfo>>> nodes = new HashMap<>();
    private final AtomicInteger directoryReads = new AtomicInteger();
    private final List<ResolveResult> observed = new ArrayList<>();
    private final SceneAssetLocator locator = new SceneAssetLocator(
            id -> holders.getOrDefault(id, CompletableFuture.completedFuture(new HolderRead(LocationStatus.MISSING, null, null))),
            (zone, node) -> {
                directoryReads.incrementAndGet();
                return nodes.getOrDefault(zone * 10_000 + node, CompletableFuture.completedFuture(Optional.empty()));
            },
            observed::add);

    private static PlayerLocation location(long player, int zone, int node) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(zone).setSceneNodeId(node).setSceneId(900)
                .setSceneConfigId(1).setOwnerEpoch(5).build();
    }

    private void online(int zone, int node) {
        holders.put(PLAYER, CompletableFuture.completedFuture(new HolderRead(LocationStatus.ONLINE,
                location(PLAYER, zone, node), null)));
    }

    private void entry(SceneNodeInfo info) {
        nodes.put(info.getZoneId() * 10_000 + info.getNodeId(), CompletableFuture.completedFuture(Optional.of(info)));
    }

    private static SceneNodeInfo.Builder info(String instance, String rpcHost, int rpcPort) {
        return SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(NODE).setInstanceId(instance)
                .setLinkHost("127.0.0.1").setLinkPort(21000).setRpcHost(rpcHost).setRpcPort(rpcPort);
    }

    private Resolution resolve() throws Exception {
        CompletableFuture<Resolution> future = locator.resolveAsync(PLAYER);
        Resolution resolution = future.get(5, TimeUnit.SECONDS);
        assertThat(future).isNotCompletedExceptionally();
        return resolution;
    }

    @Test
    void 在线且目录有资产地址_找到_带实例号() throws Exception {
        online(ZONE, NODE);
        entry(info("inst-a", "10.0.0.5", 21100).build());
        Resolution resolution = resolve();
        assertThat(resolution).isInstanceOf(Found.class);
        Found found = (Found) resolution;
        assertThat(found.endpoint()).isEqualTo(new SceneAssetEndpoint(ZONE, NODE, "inst-a", "10.0.0.5", 21100));
        assertThat(found.location().getOwnerEpoch()).isEqualTo(5);
        assertThat(observed).containsExactly(ResolveResult.FOUND);
    }

    @Test
    void 同号新实例_地址照目录给_由scene回NOT_HERE() throws Exception {
        online(ZONE, NODE);
        entry(info("inst-new", "10.0.0.6", 21100).build());
        assertThat(((Found) resolve()).endpoint().instanceId()).isEqualTo("inst-new");
    }

    @Test
    void 没有记录_租约_登出墓碑_都是没人持有_不读目录() throws Exception {
        record Case(LocationStatus status, ResolveResult expected) {
        }
        for (Case c : List.of(new Case(LocationStatus.MISSING, ResolveResult.NOT_ONLINE),
                new Case(LocationStatus.RECONNECT_LEASE, ResolveResult.LEASE),
                new Case(LocationStatus.LOGGED_OUT, ResolveResult.LOGGED_OUT))) {
            holders.put(PLAYER, CompletableFuture.completedFuture(new HolderRead(c.status(), null, null)));
            assertThat(resolve()).as(c.toString()).isEqualTo(new NoHolder(c.expected()));
        }
        assertThat(directoryReads).hasValue(0);
        assertThat(observed).containsExactly(ResolveResult.NOT_ONLINE, ResolveResult.LEASE, ResolveResult.LOGGED_OUT);
    }

    @Test
    void 位置记录损坏或Redis出错是故障_不折成不在线() throws Exception {
        holders.put(PLAYER, CompletableFuture.completedFuture(new HolderRead(LocationStatus.ERROR, null, "位置记录与键不符")));
        assertThat(resolve()).isEqualTo(new Failure("位置记录与键不符"));
        holders.put(PLAYER, CompletableFuture.failedFuture(new IllegalStateException("redis down")));
        assertThat(resolve()).isInstanceOf(Failure.class);
        SceneAssetLocator throwing = new SceneAssetLocator(id -> {
            throw new IllegalStateException("boom");
        }, (zone, node) -> CompletableFuture.completedFuture(Optional.empty()), null);
        assertThat(throwing.resolveAsync(PLAYER).get(5, TimeUnit.SECONDS)).isInstanceOf(Failure.class);
        assertThat(directoryReads).hasValue(0);
        assertThat(observed).containsExactly(ResolveResult.ERROR, ResolveResult.ERROR);
    }

    @Test
    void 记录没写节点号_不知道发给谁_没写zone是故障() throws Exception {
        online(ZONE, 0);
        assertThat(resolve()).isEqualTo(new NoHolder(ResolveResult.NODE_UNKNOWN));
        online(0, NODE);
        assertThat(resolve()).isInstanceOf(Failure.class);
        assertThat(directoryReads).hasValue(0);
    }

    @Test
    void 目录里没有这个节点_或节点不提供资产通道() throws Exception {
        online(ZONE, NODE);
        assertThat(resolve()).isEqualTo(new NoHolder(ResolveResult.NODE_UNKNOWN));
        entry(info("inst-a", "10.0.0.5", 0).build());
        assertThat(resolve()).as("旧版本节点：rpc_port = 0").isEqualTo(new NoHolder(ResolveResult.NO_RPC_PORT));
        entry(info("inst-a", " ", 21100).build());
        assertThat(resolve()).as("有端口没主机").isEqualTo(new NoHolder(ResolveResult.NO_RPC_PORT));
        entry(SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(NODE).setInstanceId("old").setLinkHost("h")
                .setLinkPort(1).build());
        assertThat(resolve()).as("不认识新字段的旧条目").isEqualTo(new NoHolder(ResolveResult.NO_RPC_PORT));
        assertThat(observed).containsExactly(ResolveResult.NODE_UNKNOWN, ResolveResult.NO_RPC_PORT,
                ResolveResult.NO_RPC_PORT, ResolveResult.NO_RPC_PORT);
    }

    @Test
    void 目录读失败或条目损坏是故障() throws Exception {
        online(ZONE, NODE);
        nodes.put(ZONE * 10_000 + NODE, CompletableFuture.failedFuture(new IllegalStateException("节点目录条目解析失败")));
        assertThat(resolve()).isInstanceOf(Failure.class);
        nodes.put(ZONE * 10_000 + NODE, CompletableFuture.completedFuture(Optional.of(
                info("inst-a", "10.0.0.5", 21100).setNodeId(NODE + 1).build())));
        assertThat(resolve()).as("条目与键不符").isInstanceOf(Failure.class);
        entry(info("inst-a", "10.0.0.5", -1).build());
        assertThat(resolve()).as("rpc_port 越界").isInstanceOf(Failure.class);
    }

    // ---- 同号节点跨 zone（spectate-spec §2.7 的 Z3 / Z7、§2.8）：scene 节点号按 zone 租约，两个 zone 的第一台都是 1 号

    @Test
    void 两个zone都有同号节点_按位置记录的zone取条目_玩家换了zone就换成那边的那台() throws Exception {
        List<String> asked = new ArrayList<>();
        SceneAssetLocator recording = new SceneAssetLocator(holders::get, (zone, node) -> {
            asked.add(zone + "/" + node);
            return nodes.getOrDefault(zone * 10_000 + node, CompletableFuture.completedFuture(Optional.empty()));
        }, null);
        entry(info("inst-here", "10.0.3.7", 21100).build());
        entry(info("inst-there", "10.0.4.7", 21110).setZoneId(ZONE + 1).build());

        online(ZONE + 1, NODE);
        Resolution there = recording.resolveAsync(PLAYER).get(5, TimeUnit.SECONDS);
        online(ZONE, NODE);
        Resolution here = recording.resolveAsync(PLAYER).get(5, TimeUnit.SECONDS);

        assertThat(((Found) there).endpoint()).isEqualTo(new SceneAssetEndpoint(ZONE + 1, NODE, "inst-there", "10.0.4.7", 21110));
        assertThat(((Found) here).endpoint()).isEqualTo(new SceneAssetEndpoint(ZONE, NODE, "inst-here", "10.0.3.7", 21100));
        assertThat(asked).as("目录的键是 (zone, 节点号)，zone 取位置记录的").containsExactly((ZONE + 1) + "/" + NODE, ZONE + "/" + NODE);
    }

    @Test
    void 玩家所在的zone没有这个节点号_别的zone有同号节点_不借用_按不知道发给谁() throws Exception {
        entry(info("inst-here", "10.0.3.7", 21100).build());
        online(ZONE + 1, NODE);

        assertThat(resolve()).isEqualTo(new NoHolder(ResolveResult.NODE_UNKNOWN));
        assertThat(directoryReads).hasValue(1);
    }

    @Test
    void 目录对别的zone的查询回了同号条目_条目的zone与位置记录不符是故障_不当成找到() throws Exception {
        online(ZONE + 1, NODE);
        // 读口出了岔子：问的是 (ZONE + 1, NODE)，回来的条目写着 ZONE
        nodes.put((ZONE + 1) * 10_000 + NODE, CompletableFuture.completedFuture(Optional.of(info("inst-here", "10.0.3.7", 21100).build())));

        Resolution resolution = resolve();

        assertThat(resolution).isInstanceOf(Failure.class);
        assertThat(((Failure) resolution).reason()).contains("与键不符").contains("entry_zone=" + ZONE);
        assertThat(observed).containsExactly(ResolveResult.ERROR);
    }

    @Test
    void 位置记录的严格读_各分支() {
        long player = 77;
        byte[] value = location(player, ZONE, NODE).toByteArray();
        assertThat(PlayerLocationDirectory.holderOf(player, Arrays.asList(null, null)).status())
                .isEqualTo(LocationStatus.MISSING);
        assertThat(PlayerLocationDirectory.holderOf(player, Arrays.asList(ascii("o"), value)))
                .isEqualTo(new HolderRead(LocationStatus.ONLINE, location(player, ZONE, NODE), null));
        assertThat(PlayerLocationDirectory.holderOf(player, Arrays.asList(ascii("l"), value)).status())
                .isEqualTo(LocationStatus.RECONNECT_LEASE);
        assertThat(PlayerLocationDirectory.holderOf(player, Arrays.asList(ascii("l"), value)).location())
                .as("租约期间没有持有者，不带位置").isNull();
        assertThat(PlayerLocationDirectory.holderOf(player, Arrays.asList(ascii("x"), null)).status())
                .isEqualTo(LocationStatus.LOGGED_OUT);
        for (List<Object> bad : List.<List<Object>>of(
                Arrays.asList(ascii("z"), value),
                Arrays.asList(null, value),
                Arrays.asList(ascii("o"), null),
                Arrays.asList(ascii("o"), new byte[0]),
                Arrays.asList(ascii("o"), new byte[] {(byte) 0xff, 0x01}),
                Arrays.asList(ascii("o"), location(player + 1, ZONE, NODE).toByteArray()),
                List.of(ascii("o")))) {
            HolderRead read = PlayerLocationDirectory.holderOf(player, bad);
            assertThat(read.status()).isEqualTo(LocationStatus.ERROR);
            assertThat(read.detail()).isNotBlank();
        }
        assertThat(PlayerLocationDirectory.holderOf(player, null).status()).isEqualTo(LocationStatus.ERROR);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
