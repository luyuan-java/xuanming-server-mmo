package com.game.discovery.drain;

import com.game.discovery.RedisKeys;
import com.game.discovery.NodeTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 运维场景疏散标记：Redis TIME 秒:实例，必须带 TTL。最后节点检查与打标记在同一 Lua 中完成，
 * 两个管理端副本同时操作不同节点也不能把同一目录快照里的节点全部标满。仅支持单节点 Redis。
 * 阻塞调用，只能在允许 I/O 的线程上执行；读异常由 scene 保持当前疏散状态。
 */
public final class SceneEvacuationMarks {
    public record Mark(long markedAtSec, String instanceId, String raw) {
        static Mark parse(String raw) {
            int colon = raw.indexOf(':');
            try {
                long seconds = Long.parseLong(raw.substring(0, colon));
                String instance = raw.substring(colon + 1);
                if (seconds <= 0 || !validInstance(instance)) {
                    throw new IllegalArgumentException("无效时间或实例");
                }
                return new Mark(seconds, instance, raw);
            } catch (RuntimeException e) {
                // 坏值不是撤销：scene 的读失败分支必须保留当前状态。
                throw new IllegalStateException("场景疏散标记损坏", e);
            }
        }

        public boolean appliesTo(String currentInstance) {
            return instanceId.equals(currentInstance);
        }
    }

    public enum MarkResult {
        MARKED, ALREADY_EVACUATING, LAST_NODE, EXISTING_INVALID, TARGET_CHANGED, ZONE_RETIRED
    }

    // KEYS: 目标标记、目标租约、永久退役、其余节点的(标记,租约)；ARGV: ttl、目标实例、是否检查、其余节点实例。
    private static final String MARK = """
            if redis.call('exists', KEYS[3]) ~= 0 then return 6 end
            if redis.call('get', KEYS[2]) ~= ARGV[2] then return 5 end
            local cur = redis.call('get', KEYS[1])
            if cur then
              local split = string.find(cur, ':', 1, true)
              if split and string.sub(cur, split + 1) == ARGV[2] then
                local stamp = string.sub(cur, 1, split - 1)
                if not string.match(stamp, '^%d+$') or tonumber(stamp) <= 0
                    or redis.call('pttl', KEYS[1]) < 0 then
                  return 4
                end
                return 2
              end
            end
            if ARGV[3] == '1' then
              local accepting = false
              for i = 4, #KEYS, 2 do
                local other = redis.call('get', KEYS[i])
                local split = other and string.find(other, ':', 1, true)
                local identity = ARGV[(i + 4) / 2]
                if redis.call('get', KEYS[i + 1]) == identity
                    and (not other or not split or string.sub(other, split + 1) ~= identity) then
                  accepting = true
                  break
                end
              end
              if not accepting then return 3 end
            end
            local now = redis.call('time')[1]
            redis.call('set', KEYS[1], now .. ':' .. ARGV[2], 'EX', ARGV[1])
            return 1
            """;

    private final RedissonClient redis;
    private final RedisKeys keys;

    public SceneEvacuationMarks(RedissonClient redis, RedisKeys keys) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** 不存在返回 null，旧实例标记原样返回，由调用方比较实例；异常不得当作没有。 */
    public Mark read(int zoneId, int nodeId) {
        String raw = redis.<String>getBucket(keys.sceneEvacuating(zoneId, nodeId), StringCodec.INSTANCE).get();
        return raw == null ? null : Mark.parse(raw);
    }

    /** 一次 MGET 读取目录对应的标记；空目录不访问 Redis。 */
    public Map<Integer, Mark> evacuating(int zoneId, Collection<Integer> nodeIds) {
        if (nodeIds.isEmpty()) return Map.of();
        Map<String, Integer> nodes = new HashMap<>();
        for (int node : nodeIds) nodes.put(keys.sceneEvacuating(zoneId, node), node);
        Map<String, String> found = redis.getBuckets(StringCodec.INSTANCE).get(nodes.keySet().toArray(String[]::new));
        Map<Integer, Mark> result = new HashMap<>();
        found.forEach((key, raw) -> {
            if (raw != null) result.put(nodes.get(key), Mark.parse(raw));
        });
        return Map.copyOf(result);
    }

    /** others 是目录里其余可接客节点的号与实例；null 显式表示 force，空集合必须拒绝最后一台。 */
    public MarkResult mark(int zoneId, int nodeId, String instance, Duration ttl, Map<Integer, String> others) {
        if (!validInstance(instance) || ttl.toSeconds() <= 0) {
            throw new IllegalArgumentException("场景实例与正 TTL 必填");
        }
        List<Object> scriptKeys = new ArrayList<>(List.of(keys.sceneEvacuating(zoneId, nodeId),
                keys.nodeId(NodeTypes.SCENE, zoneId, nodeId), keys.worldRetirement(zoneId)));
        List<Object> args = new ArrayList<>(List.of(Long.toString(ttl.toSeconds()), instance, others == null ? "0" : "1"));
        if (others != null) others.forEach((other, identity) -> {
            if (other == nodeId || !validInstance(identity)) {
                throw new IllegalArgumentException("其余节点不能包含自身或空实例");
            }
            scriptKeys.add(keys.sceneEvacuating(zoneId, other));
            scriptKeys.add(keys.nodeId(NodeTypes.SCENE, zoneId, other));
            args.add(identity);
        });
        Long result = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, MARK,
                RScript.ReturnType.INTEGER, scriptKeys, args.toArray());
        if (result == null || result < 1 || result > 6) throw new IllegalStateException("场景疏散脚本没有返回有效结局");
        return switch (result.intValue()) {
            case 1 -> MarkResult.MARKED;
            case 2 -> MarkResult.ALREADY_EVACUATING;
            case 3 -> MarkResult.LAST_NODE;
            case 4 -> MarkResult.EXISTING_INVALID;
            case 5 -> MarkResult.TARGET_CHANGED;
            case 6 -> MarkResult.ZONE_RETIRED;
            default -> throw new IllegalStateException("场景疏散脚本返回未知结局");
        };
    }

    /** 运维撤销；不存在也成功。 */
    public void clear(int zoneId, int nodeId) {
        redis.getKeys().delete(keys.sceneEvacuating(zoneId, nodeId));
    }

    private static boolean validInstance(String instance) {
        return instance != null && !instance.isBlank() && instance.indexOf(':') < 0;
    }
}
