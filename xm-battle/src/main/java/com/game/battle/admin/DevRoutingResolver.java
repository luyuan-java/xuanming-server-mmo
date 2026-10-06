package com.game.battle.admin;

import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.redisson.api.RedissonClient;

/**
 * dev 管理接口的快照路由补全（battle-node-spec §7.12）：robot 建房时快照里不填路由（{@code routing.gate_instance_id} 为空），由这里按玩家此刻的
 * 在线状态补全，补不全就不建房（422）。dev 建房的快照是调用方给的、不经 scene 出（6.3 起由 scene 出快照的是 dev gather），这是它能端到端跑通的前提。
 *
 * <ul>
 *   <li><b>gate 部分</b>（{@code session_id / gate_node_id / gate_instance_id / zone_id}）：在线目录 {@code xm:presence:{pid}}（严格读：条目损坏是故障，
 *       不当成离线）；</li>
 *   <li><b>scene 部分</b>（{@code scene_node_id / scene_instance_id}，只有参战者要）：位置记录 {@code xm:location:{pid}} 只认在线状态 {@code o}
 *       （同 {@code SceneAssetLocator}；重连租约 / 登出墓碑 / 没有记录都算补不全）+ scene 目录（zone = 位置记录的 zone）里该节点的 {@code instance_id}。</li>
 *   <li>观众路由的 scene 字段恒为 0（同基线 {@code room.h:197-202}）。</li>
 *   <li>快照里已带 {@code gate_instance_id} 的原样保留（调用方给了完整路由）。</li>
 * </ul>
 * 读失败 / 超时 / 条目损坏抛 {@link LookupException}（接口回 503，与「补不全」的 422 分开）。阻塞调用：只在管理 Tomcat 线程上用，
 * 不得在逻辑线程上调。线程安全。
 */
public final class DevRoutingResolver {

    /** 单次 Redis 读的上限。 */
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    /** 三种读（生产实现读 Redis，阻塞到结果或超时；失败抛异常）。 */
    public interface Lookups {

        /** 在线目录（严格读：条目损坏 / 与键不符抛异常）；不在线为空。 */
        Optional<PlayerPresence> presence(long playerId) throws Exception;

        /** 位置记录的严格读（{@link PlayerLocationDirectory#findHolderAsync}）。 */
        HolderRead location(long playerId) throws Exception;

        /** scene 节点目录的单条读；没有为空。 */
        Optional<SceneNodeInfo> sceneNode(int zoneId, int nodeId) throws Exception;
    }

    /** 读在线目录 / 位置记录 / scene 目录失败（故障，不是「补不全」）。 */
    public static final class LookupException extends Exception {
        LookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 补全结果。
     *
     * @param request    补全后的请求（{@link #ok()} 时有效）
     * @param unresolved 补不全的原因（逐人一条）；为空 = 全部补全
     */
    public record Resolution<T>(T request, List<String> unresolved) {

        public Resolution {
            unresolved = List.copyOf(unresolved);
        }

        public boolean ok() {
            return unresolved.isEmpty();
        }
    }

    private final Lookups lookups;

    public DevRoutingResolver(Lookups lookups) {
        this.lookups = Objects.requireNonNull(lookups, "lookups");
    }

    /** 生产装配：三种读都来自同一个 Redis。 */
    public static DevRoutingResolver redis(RedissonClient redis) {
        PlayerPresenceDirectory presence = new PlayerPresenceDirectory(redis);
        PlayerLocationDirectory locations = new PlayerLocationDirectory(redis);
        NodeDirectory<SceneNodeInfo> scenes = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        return new DevRoutingResolver(new Lookups() {
            @Override
            public Optional<PlayerPresence> presence(long playerId) throws Exception {
                return await(presence.findStrictAsync(playerId).toCompletableFuture());
            }

            @Override
            public HolderRead location(long playerId) throws Exception {
                return await(locations.findHolderAsync(playerId));
            }

            @Override
            public Optional<SceneNodeInfo> sceneNode(int zoneId, int nodeId) throws Exception {
                return await(scenes.findAsync(zoneId, nodeId));
            }
        });
    }

    /** 建房：给 {@code routing.gate_instance_id} 为空的每个快照补全完整路由。 */
    public Resolution<CreateBattleRequest> fillCreate(CreateBattleRequest request) throws LookupException {
        CreateBattleRequest.Builder out = request.toBuilder();
        List<String> unresolved = new ArrayList<>();
        for (int i = 0; i < request.getPlayersCount(); i++) {
            BattlePlayerSnapshot snapshot = request.getPlayers(i);
            if (!snapshot.getRouting().getGateInstanceId().isEmpty()) {
                continue;
            }
            long playerId = snapshot.getPlayerId();
            Fill fill = participantRouting(playerId);
            if (fill.reason() != null) {
                unresolved.add(fill.reason());
            } else {
                out.setPlayers(i, snapshot.toBuilder().setRouting(fill.routing()));
            }
        }
        return new Resolution<>(out.build(), unresolved);
    }

    /** 登记观众：{@code routing.gate_instance_id} 为空时补全 gate 部分（scene 字段恒为 0）。 */
    public Resolution<AddObserverRequest> fillObserver(AddObserverRequest request) throws LookupException {
        if (!request.getRouting().getGateInstanceId().isEmpty()) {
            return new Resolution<>(request, List.of());
        }
        long playerId = request.getObserverPlayerId();
        Optional<PlayerPresence> online = presence(playerId);
        String gateProblem = gateProblem(playerId, online);
        if (gateProblem != null) {
            return new Resolution<>(request, List.of(gateProblem));
        }
        return new Resolution<>(request.toBuilder().setRouting(gateRouting(online.get())).build(), List.of());
    }

    // ---------------------------------------------------------------- 内部

    private record Fill(BattleRouting routing, String reason) {
    }

    private Fill participantRouting(long playerId) throws LookupException {
        if (playerId == 0) {
            return new Fill(null, "快照的 player_id 为 0，无法补全路由");
        }
        Optional<PlayerPresence> online = presence(playerId);
        String gateProblem = gateProblem(playerId, online);
        if (gateProblem != null) {
            return new Fill(null, gateProblem);
        }
        HolderRead holder;
        try {
            holder = lookups.location(playerId);
        } catch (Exception e) {
            throw new LookupException("读位置记录失败 player_id=" + Long.toUnsignedString(playerId), e);
        }
        switch (holder.status()) {
            case ONLINE -> {
                // 往下查 scene 目录
            }
            case ERROR -> throw new LookupException("位置记录状态未知 player_id=" + Long.toUnsignedString(playerId) + "："
                    + holder.detail(), null);
            default -> {
                return new Fill(null, "player_id=" + Long.toUnsignedString(playerId) + " 的位置记录不是在线状态（"
                        + holder.status() + "），补不全 scene 路由");
            }
        }
        PlayerLocation location = holder.location();
        if (location == null || location.getSceneNodeId() == 0) {
            return new Fill(null, "player_id=" + Long.toUnsignedString(playerId) + " 的位置记录没写 scene 节点号");
        }
        Optional<SceneNodeInfo> scene;
        try {
            scene = lookups.sceneNode(location.getZoneId(), location.getSceneNodeId());
        } catch (Exception e) {
            throw new LookupException("读 scene 节点目录失败 zone=" + Integer.toUnsignedString(location.getZoneId()) + " node="
                    + Integer.toUnsignedString(location.getSceneNodeId()), e);
        }
        if (scene.isEmpty() || scene.get().getInstanceId().isEmpty()) {
            return new Fill(null, "scene 目录里没有 player_id=" + Long.toUnsignedString(playerId) + " 所在的节点 zone="
                    + Integer.toUnsignedString(location.getZoneId()) + " node=" + Integer.toUnsignedString(location.getSceneNodeId()));
        }
        BattleRouting routing = gateRouting(online.get()).toBuilder()
                .setSceneNodeId(location.getSceneNodeId())
                .setSceneInstanceId(scene.get().getInstanceId())
                .build();
        return new Fill(routing, null);
    }

    private Optional<PlayerPresence> presence(long playerId) throws LookupException {
        if (playerId == 0) {
            return Optional.empty();
        }
        try {
            return lookups.presence(playerId);
        } catch (Exception e) {
            throw new LookupException("读在线目录失败 player_id=" + Long.toUnsignedString(playerId), e);
        }
    }

    private static String gateProblem(long playerId, Optional<PlayerPresence> online) {
        if (online.isEmpty()) {
            return "player_id=" + Long.toUnsignedString(playerId) + " 不在线（在线目录没有条目），补不全 gate 路由";
        }
        if (online.get().getGateInstanceId().isEmpty()) {
            return "player_id=" + Long.toUnsignedString(playerId) + " 的在线目录条目没有 gate 实例";
        }
        return null;
    }

    private static BattleRouting gateRouting(PlayerPresence presence) {
        return BattleRouting.newBuilder()
                .setSessionId(presence.getSessionId())
                .setGateNodeId(presence.getGateNodeId())
                .setGateInstanceId(presence.getGateInstanceId())
                .setZoneId(presence.getZoneId())
                .build();
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get(LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ex ? ex : e;
        } catch (TimeoutException e) {
            future.cancel(false);
            throw e;
        }
    }
}
