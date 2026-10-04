package com.game.discovery.drain;

import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * gate 排空的 Redis 标记（同 mmorpg loginqueue gatedrain / gatedrain_monitor 的键模型，键走 {@link RedisKeys}）：
 * <ul>
 *   <li>{@code draining}：运维打的「不再分新玩家」标记，值 = {@code 打标记时刻（Redis 服务器时间，秒）:gate 实例 id}，
 *       <b>必须带 TTL</b>。标记绑实例：节点号会被新实例复用，旧实例的标记对新实例不算数（读方按实例比对，判定循环顺手清掉）；</li>
 *   <li>{@code drained}：判定循环写的「已可安全下线」信号，值 = 理由，TTL = draining 的剩余 TTL（不比它活得久），
 *       只在 draining 仍是判定时看到的那一份时写。</li>
 * </ul>
 * 只支持单节点 Redis（与其余键一样）。线程安全；阻塞（Redis），只在允许阻塞的线程上调用。
 */
public final class GateDrainMarks {

    public static final String REASON_BELOW_THRESHOLD = "below_threshold";
    public static final String REASON_DEADLINE = "deadline";

    /** 一份排空标记。{@code raw} 是存储里的原值（比较并删除 / 写 drained 时用来确认还是同一份）。 */
    public record Mark(long markedAtSec, String instanceId, String raw) {

        static Mark parse(String raw) {
            int colon = raw.indexOf(':');
            long sec = 0;
            try {
                sec = Long.parseLong(colon < 0 ? raw : raw.substring(0, colon));
            } catch (NumberFormatException e) {
                // 坏值：起点按 0 算（判定按刚打上算），实例对不上任何 gate
            }
            return new Mark(sec, colon < 0 ? "" : raw.substring(colon + 1), raw);
        }

        /** 这份标记是不是打给这个实例的。 */
        public boolean appliesTo(String gateInstanceId) {
            return instanceId.equals(gateInstanceId);
        }
    }

    /** 打标记的结局。 */
    public enum MarkResult {
        /** 新打上（或替换了旧实例留下的标记）。 */
        MARKED,
        /** 这个实例已在排空：起点不变、TTL 不续。 */
        ALREADY_DRAINING,
        /** 打上之后本区没有接客的 gate 了（没带 force）：没打。 */
        LAST_GATE,
        /** 已有的同实例标记没有 TTL（手工写坏的）：没动，先撤销再打。 */
        EXISTING_INVALID
    }

    /** 写 drained 的结局。 */
    public enum DrainedWrite {
        WRITTEN,
        /** 排空标记已不在、或已不是判定时那一份：没写。 */
        MARK_CHANGED,
        /** 排空标记没有 TTL（手工写坏的，永远不会过期）：没写。 */
        NO_TTL
    }

    /**
     * 打标记（原子）：已有同实例的标记 → 2（它没有 TTL → 4）；要求检查「最后一台」时，其余 gate 里没有一台在接客（没标记，或标记是旧实例的）→ 3；
     * 否则先清残留 drained、SET {@code 服务器时间:实例} EX ttl → 1。
     * KEYS: draining, drained, 其余 gate 的 draining…；ARGV: ttl_sec, instance, check_last('1'/'0'), 其余 gate 的实例…
     */
    private static final String MARK = """
            local cur = redis.call('get', KEYS[1])
            if cur and string.sub(cur, (string.find(cur, ':', 1, true) or 0) + 1) == ARGV[2] then
              if redis.call('pttl', KEYS[1]) < 0 then
                return 4
              end
              return 2
            end
            if ARGV[3] == '1' then
              local accepting = false
              for i = 3, #KEYS do
                local other = redis.call('get', KEYS[i])
                if not other or string.sub(other, (string.find(other, ':', 1, true) or 0) + 1) ~= ARGV[i + 1] then
                  accepting = true
                  break
                end
              end
              if not accepting then
                return 3
              end
            end
            redis.call('del', KEYS[2])
            local now = redis.call('time')[1]
            redis.call('set', KEYS[1], now .. ':' .. ARGV[2], 'EX', ARGV[1])
            return 1
            """;

    /**
     * 写 drained：draining 必须仍是判定时看到的那一份（ARGV[2]），TTL 取它的剩余 TTL；不在 / 换了 / 没有 TTL 就不写
     * （不能回落成固定时长——drained 比 draining 活得久，同一节点号下次再标排空时残留的 drained 会被读成「已排空」）。
     * KEYS: draining, drained；ARGV: reason, expected_raw。返回 1 写了 / 0 标记变了 / -1 标记没有 TTL。
     */
    private static final String MARK_DRAINED = """
            if redis.call('get', KEYS[1]) ~= ARGV[2] then
              return 0
            end
            local ttl = redis.call('pttl', KEYS[1])
            if ttl == -1 then
              return -1
            end
            if ttl <= 0 then
              return 0
            end
            redis.call('set', KEYS[2], ARGV[1], 'PX', ttl)
            return 1
            """;

    /** 比较并删除：draining 仍是 ARGV[1] 时两个标记一起删。KEYS: draining, drained。 */
    private static final String CLEAR_IF = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              redis.call('del', KEYS[1], KEYS[2])
              return 1
            end
            return 0
            """;

    private final RedissonClient redis;

    public GateDrainMarks(RedissonClient redis) {
        this.redis = redis;
    }

    /**
     * @param instanceId 目标 gate 当前的实例 id（标记绑实例）
     * @param ttl        标记有效期（到期后这台 gate 自动重新接客）
     * @param others     本区其余 gate：节点号 → 实例 id；为 null 表示不做「最后一台」检查（force）
     */
    public MarkResult mark(int zoneId, int nodeId, String instanceId, Duration ttl, Map<Integer, String> others) {
        if (ttl.toSeconds() <= 0) {
            throw new IllegalArgumentException("排空标记必须带正的 TTL");
        }
        if (instanceId == null || instanceId.isEmpty() || instanceId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("gate 实例 id 不合法");
        }
        List<Object> keys = new ArrayList<>(List.of(RedisKeys.gateDraining(zoneId, nodeId), RedisKeys.gateDrained(zoneId, nodeId)));
        List<Object> args = new ArrayList<>(List.of(Long.toString(ttl.toSeconds()), instanceId, others == null ? "0" : "1"));
        if (others != null) {
            others.forEach((node, instance) -> {
                keys.add(RedisKeys.gateDraining(zoneId, node));
                args.add(instance);
            });
        }
        Long result = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, MARK, RScript.ReturnType.INTEGER,
                keys, args.toArray());
        long r = result == null ? 0 : result;
        return switch ((int) r) {
            case 2 -> MarkResult.ALREADY_DRAINING;
            case 3 -> MarkResult.LAST_GATE;
            case 4 -> MarkResult.EXISTING_INVALID;
            default -> MarkResult.MARKED;
        };
    }

    /** 撤销排空（取消缩容 / 已下线清理）：两个标记一起删。 */
    public void clear(int zoneId, int nodeId) {
        redis.getKeys().delete(RedisKeys.gateDraining(zoneId, nodeId), RedisKeys.gateDrained(zoneId, nodeId));
    }

    /** 标记仍是 {@code mark} 那一份时两个一起删（清旧实例留下的标记，不误删刚打上的新标记）。 */
    public boolean clearIf(int zoneId, int nodeId, Mark mark) {
        Long r = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, CLEAR_IF, RScript.ReturnType.INTEGER,
                List.of(RedisKeys.gateDraining(zoneId, nodeId), RedisKeys.gateDrained(zoneId, nodeId)), mark.raw());
        return r != null && r == 1;
    }

    /** 一次 MGET 问完：节点号 → 排空标记（含实例，调用方按实例比对）；没有的不出现。 */
    public Map<Integer, Mark> draining(int zoneId, Collection<Integer> nodeIds) {
        Map<Integer, Mark> out = new HashMap<>();
        mget(zoneId, nodeIds, true).forEach((node, raw) -> out.put(node, Mark.parse(raw)));
        return out;
    }

    /** 一次 MGET 问完：节点号 → drained 理由；没有的不出现。 */
    public Map<Integer, String> drained(int zoneId, Collection<Integer> nodeIds) {
        return mget(zoneId, nodeIds, false);
    }

    /** 写 drained（TTL 跟着排空标记；标记已不是 {@code mark} 那一份、或没有 TTL 时不写）。 */
    public DrainedWrite markDrained(int zoneId, int nodeId, Mark mark, String reason) {
        Long written = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, MARK_DRAINED,
                RScript.ReturnType.INTEGER,
                List.of(RedisKeys.gateDraining(zoneId, nodeId), RedisKeys.gateDrained(zoneId, nodeId)), reason, mark.raw());
        long w = written == null ? 0 : written;
        return w == 1 ? DrainedWrite.WRITTEN : w == -1 ? DrainedWrite.NO_TTL : DrainedWrite.MARK_CHANGED;
    }

    /** 清掉 drained（没在排空、或已不再满足排空条件的 gate 不该带着它）。 */
    public void clearDrained(int zoneId, int nodeId) {
        redis.getKeys().delete(RedisKeys.gateDrained(zoneId, nodeId));
    }

    /** Redis 服务器时间（秒）：与标记起点同一个时钟，算「已等多久」不受各副本时钟偏差影响。 */
    public long serverTimeSec() {
        String sec = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, "return redis.call('time')[1]",
                RScript.ReturnType.VALUE, List.of());
        return Long.parseLong(sec);
    }

    private Map<Integer, String> mget(int zoneId, Collection<Integer> nodeIds, boolean draining) {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(nodeIds.size());
        Map<String, Integer> nodeOf = new HashMap<>();
        for (int node : nodeIds) {
            String key = draining ? RedisKeys.gateDraining(zoneId, node) : RedisKeys.gateDrained(zoneId, node);
            keys.add(key);
            nodeOf.put(key, node);
        }
        Map<String, String> found = redis.getBuckets(StringCodec.INSTANCE).get(keys.toArray(String[]::new));
        Map<Integer, String> out = new HashMap<>();
        found.forEach((key, value) -> {
            if (value != null) {
                out.put(nodeOf.get(key), value);
            }
        });
        return out;
    }
}
