package com.game.gateway.admin;

import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 基于 Redis 哈希 {@code <ns>:killswitch} 的热关停规则存储（与各节点 {@code RedisKillSwitchSync} 读写同一键），
 * 并经在线节点目录核对 {@code node_type} 与 {@code instance} 是否在线。
 */
public final class RedisKillSwitchRuleStore implements KillSwitchRuleStore {

    private final RedissonClient redis;
    private final RedisKeys keys;
    private final ZoneDirectory zones;

    public RedisKillSwitchRuleStore(RedissonClient redis, RedisKeys keys, ZoneDirectory zones) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.zones = zones;
    }

    private RMap<String, String> map() {
        return redis.getMap(keys.killSwitch(), StringCodec.INSTANCE);
    }

    @Override
    public Map<String, String> list() {
        return map().readAllMap();
    }

    @Override
    public void put(String relativeKey, String value) {
        map().put(relativeKey, value);
    }

    @Override
    public long delete(String relativeKey) {
        return map().fastRemove(relativeKey);
    }

    @Override
    public Set<String> registeredNodeUuids(String nodeType) {
        Set<String> out = new HashSet<>();
        switch (nodeType) {
            case "GateNodeService" -> {
                NodeDirectory<GateNodeInfo> dir = new NodeDirectory<>(redis, keys, NodeTypes.GATE, GateNodeInfo.parser());
                for (int zoneId : activeZoneIds()) {
                    for (GateNodeInfo info : dir.list(zoneId)) {
                        addUuid(out, info.getInstanceId());
                    }
                }
            }
            case "SceneNodeService" -> {
                NodeDirectory<SceneNodeInfo> dir = new NodeDirectory<>(redis, keys, NodeTypes.SCENE, SceneNodeInfo.parser());
                for (int zoneId : activeZoneIds()) {
                    for (SceneNodeInfo info : dir.list(zoneId)) {
                        addUuid(out, info.getInstanceId());
                    }
                }
            }
            case "BattleNodeService" -> {
                NodeDirectory<BattleNodeInfo> dir = new NodeDirectory<>(redis, keys, NodeTypes.BATTLE, BattleNodeInfo.parser());
                for (BattleNodeInfo info : dir.list(0)) {
                    addUuid(out, info.getInstanceId());
                }
            }
            default -> {
                // 其他以雪花租约注册的服务按租约键查活跃节点实例 UUID（键值为 <instanceId>:<epoch> 或 <instanceId>）
                String leaseType = leaseTypeOf(nodeType);
                if (!leaseType.isEmpty()) {
                    for (String leaseKey : redis.getKeys().getKeysByPattern(keys.prefix() + "node-id:" + leaseType + ":*")) {
                        Object raw = redis.getBucket(leaseKey, StringCodec.INSTANCE).get();
                        if (raw instanceof String text && !text.isBlank()) {
                            int sep = text.indexOf(':');
                            addUuid(out, sep > 0 ? text.substring(0, sep) : text);
                        }
                    }
                }
            }
        }
        return out;
    }

    private Set<Integer> activeZoneIds() {
        Set<Integer> ids = new HashSet<>();
        if (zones != null) {
            for (ZoneRow z : zones.zones()) {
                if (z.zoneId() > 0) {
                    ids.add(z.zoneId());
                }
            }
        }
        return ids;
    }

    private static void addUuid(Set<String> out, String raw) {
        if (raw != null && !raw.isBlank()) {
            out.add(raw.strip().toLowerCase(Locale.ROOT));
        }
    }

    private static String leaseTypeOf(String nodeType) {
        return switch (nodeType) {
            case "LoginNodeService" -> NodeTypes.LOGIN;
            case "FriendNodeService" -> NodeTypes.FRIEND_REQUEST;
            case "TeamNodeService" -> NodeTypes.TEAM;
            case "GuildNodeService" -> NodeTypes.GUILD;
            case "TradeNodeService" -> NodeTypes.TRADE;
            case "SceneManagerNodeService" -> NodeTypes.SCENE_MANAGER;
            case "MatchNodeService" -> NodeTypes.MATCH;
            default -> "";
        };
    }
}
