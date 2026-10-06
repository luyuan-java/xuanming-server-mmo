package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.port.PlayerStatusReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link PlayerStatusReader} 的测试替身：内存里的战斗锁、在线目录、位置记录，按玩家预置；三样读各自可以注入失败；记下读的次序
 * （判定顺序本身是语义——「先锁、再票、再位置」「在线 → 锁 → 位置 → 票」都靠它断言）。
 *
 * <pre>
 * FakePlayerStatus players = new FakePlayerStatus();
 * players.online(1001, 1, 7);                      // 在线：有在线目录条目，位置 ONLINE、zone 1、scene 节点 7
 * players.inBattle(1001, true);                    // 有战斗锁
 * players.location(1002, LocationStatus.RECONNECT_LEASE);   // 断线重连租约中（在线目录没有条目）
 * players.failLock(1003);                          // 读 1003 的战斗锁失败（failPresence / failLocation 同理）
 * assertThat(players.reads).containsExactly("lock:1001", "location:1001");
 * </pre>
 * 没预置过的玩家：没有锁、不在线、位置 MISSING。{@link #holderAsync} 给需要异步严格读的地方用（{@code SceneAssetLocator} 的构造参数）。线程安全。
 */
public final class FakePlayerStatus implements PlayerStatusReader {

    /** 读的次序：{@code "lock:<pid>"} / {@code "presence:<pid>"} / {@code "location:<pid>"}。 */
    public final List<String> reads = new CopyOnWriteArrayList<>();
    private final Map<Long, Boolean> locks = new ConcurrentHashMap<>();
    private final Map<Long, PlayerPresence> presences = new ConcurrentHashMap<>();
    private final Map<Long, HolderRead> locations = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 预置

    /** 在游戏里：在线目录有条目（gate 节点 1、会话号 = 玩家号低 31 位），位置 ONLINE 指向 (zone, scene 节点)。 */
    public FakePlayerStatus online(long playerId, int zoneId, int sceneNodeId) {
        presences.put(playerId, PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setGateNodeId(1)
                .setGateInstanceId("gate-inst-1").setSessionId((int) (playerId & 0x7FFF_FFFF)).setOwnerEpoch(1).build());
        return location(playerId, zoneId, sceneNodeId);
    }

    /** 位置 ONLINE（不动在线目录）。 */
    public FakePlayerStatus location(long playerId, int zoneId, int sceneNodeId) {
        locations.put(playerId, new HolderRead(LocationStatus.ONLINE, PlayerLocation.newBuilder().setPlayerId(playerId).setZoneId(zoneId)
                .setSceneNodeId(sceneNodeId).setSceneId(1000L + sceneNodeId).setOwnerEpoch(1).build(), null));
        return this;
    }

    /** 位置是没有持有者的三种状态之一（RECONNECT_LEASE / LOGGED_OUT / MISSING）。 */
    public FakePlayerStatus location(long playerId, LocationStatus status) {
        if (status == LocationStatus.ONLINE || status == LocationStatus.ERROR) {
            throw new IllegalArgumentException("ONLINE 用 location(pid, zone, node)，ERROR 用 failLocation(pid)");
        }
        locations.put(playerId, new HolderRead(status, null, null));
        return this;
    }

    /** 下线：摘掉在线目录条目，位置改成重连租约（Java 断线即移除实体）。 */
    public FakePlayerStatus disconnected(long playerId) {
        presences.remove(playerId);
        return location(playerId, LocationStatus.RECONNECT_LEASE);
    }

    /** 登出：摘掉在线目录条目，位置改成登出墓碑。 */
    public FakePlayerStatus loggedOut(long playerId) {
        presences.remove(playerId);
        return location(playerId, LocationStatus.LOGGED_OUT);
    }

    public FakePlayerStatus presence(long playerId, PlayerPresence presence) {
        presences.put(playerId, presence);
        return this;
    }

    public FakePlayerStatus inBattle(long playerId, boolean locked) {
        locks.put(playerId, locked);
        return this;
    }

    public FakePlayerStatus failLock(long playerId) {
        failing.put("lock:" + playerId, true);
        return this;
    }

    public FakePlayerStatus failPresence(long playerId) {
        failing.put("presence:" + playerId, true);
        return this;
    }

    public FakePlayerStatus failLocation(long playerId) {
        failing.put("location:" + playerId, true);
        return this;
    }

    /** 清掉这名玩家的全部故障注入。 */
    public FakePlayerStatus heal(long playerId) {
        failing.remove("lock:" + playerId);
        failing.remove("presence:" + playerId);
        failing.remove("location:" + playerId);
        return this;
    }

    // ---------------------------------------------------------------- PlayerStatusReader

    @Override
    public boolean inBattle(long playerId, Deadline d) {
        enter("lock", playerId, "读战斗锁");
        return locks.getOrDefault(playerId, false);
    }

    @Override
    public Optional<PlayerPresence> presence(long playerId, Deadline d) {
        enter("presence", playerId, "读在线目录");
        return Optional.ofNullable(presences.get(playerId));
    }

    @Override
    public HolderRead location(long playerId, Deadline d) {
        enter("location", playerId, "读位置记录");
        return locations.getOrDefault(playerId, new HolderRead(LocationStatus.MISSING, null, null));
    }

    /**
     * 位置的异步严格读（{@code PlayerLocationDirectory.findHolderAsync} 的形状：future 从不异常完成，故障是 {@code ERROR} 状态）。
     * 用法：{@code new SceneAssetLocator(players::holderAsync, sceneNodes, null)}。同样记进 {@link #reads}。
     */
    public CompletableFuture<HolderRead> holderAsync(long playerId) {
        reads.add("location:" + Long.toUnsignedString(playerId));
        if (failing.containsKey("location:" + playerId)) {
            return CompletableFuture.completedFuture(new HolderRead(LocationStatus.ERROR, null, "注入的故障: 读位置记录"));
        }
        return CompletableFuture.completedFuture(locations.getOrDefault(playerId, new HolderRead(LocationStatus.MISSING, null, null)));
    }

    private void enter(String aspect, long playerId, String what) {
        reads.add(aspect + ":" + Long.toUnsignedString(playerId));
        if (failing.containsKey(aspect + ":" + playerId)) {
            throw new Deadline.DependencyException("注入的故障: " + what + " player=" + Long.toUnsignedString(playerId));
        }
    }
}
