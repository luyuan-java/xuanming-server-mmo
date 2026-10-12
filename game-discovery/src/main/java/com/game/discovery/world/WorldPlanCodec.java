package com.game.discovery.world;

import com.game.api.proto.WorldChannel;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RedissonWorldChannelStore} 的脚本参数编码与回复解析（纯函数，无 I/O，包内单测直接驱动）。
 *
 * <p>脚本走 ByteArrayCodec（频道记录是 pb 任意字节）：参数一律 {@code byte[]}，数字是 ASCII 十进制；回复里整数是 {@link Long}、
 * 字符串是 {@code byte[]}（为了健壮也接受 {@link String}）。
 */
final class WorldPlanCodec {

    private static final Logger log = LoggerFactory.getLogger(WorldPlanCodec.class);

    private WorldPlanCodec() {
    }

    // ---------------------------------------------------------------- 写入批次

    /** 写入 Lua 的 ARGV：{@code token, 期望 ver, 写入后的 ver, (op, field, value)…}（§4.4）。 */
    static List<byte[]> writeArgs(String leaderToken, WorldPlanBatch batch) {
        List<byte[]> args = new ArrayList<>(3 + batch.size() * 3);
        args.add(ascii(leaderToken));
        args.add(ascii(Long.toString(batch.expectedVersion())));
        args.add(ascii(Long.toString(batch.writtenVersion())));
        for (WorldPlanOp op : batch.ops()) {
            args.add(ascii(code(op)));
            args.add(ascii(field(op)));
            args.add(value(op));
        }
        return args;
    }

    /** 改动的 op 码（写入 Lua 的分派键）。 */
    static String code(WorldPlanOp op) {
        return switch (op) {
            case WorldPlanOp.PutChannel ignored -> "S";
            case WorldPlanOp.RemoveChannel ignored -> "D";
            case WorldPlanOp.SetDesired ignored -> "Q";
            case WorldPlanOp.SeedDesired ignored -> "N";
            case WorldPlanOp.RemoveDesired ignored -> "X";
            case WorldPlanOp.SetCooldown ignored -> "C";
            case WorldPlanOp.RemoveCooldown ignored -> "Y";
        };
    }

    /** HASH 字段：频道表是 scene_id、期望数 / 冷却表是 conf，都是无符号十进制。 */
    static String field(WorldPlanOp op) {
        return switch (op) {
            case WorldPlanOp.PutChannel put -> Long.toUnsignedString(put.channel().getSceneId());
            case WorldPlanOp.RemoveChannel remove -> Long.toUnsignedString(remove.sceneId());
            case WorldPlanOp.SetDesired set -> Integer.toUnsignedString(set.sceneConfigId());
            case WorldPlanOp.SeedDesired seed -> Integer.toUnsignedString(seed.sceneConfigId());
            case WorldPlanOp.RemoveDesired remove -> Integer.toUnsignedString(remove.sceneConfigId());
            case WorldPlanOp.SetCooldown set -> Integer.toUnsignedString(set.sceneConfigId());
            case WorldPlanOp.RemoveCooldown remove -> Integer.toUnsignedString(remove.sceneConfigId());
        };
    }

    /** HASH 值：频道记录是 pb，期望数 / 冷却是十进制；删除类为空串（脚本不用）。 */
    static byte[] value(WorldPlanOp op) {
        return switch (op) {
            case WorldPlanOp.PutChannel put -> put.channel().toByteArray();
            case WorldPlanOp.SetDesired set -> ascii(Integer.toString(set.count()));
            case WorldPlanOp.SeedDesired seed -> ascii(Integer.toString(seed.count()));
            case WorldPlanOp.SetCooldown set -> ascii(Long.toString(set.untilMs()));
            case WorldPlanOp.RemoveChannel ignored -> new byte[0];
            case WorldPlanOp.RemoveDesired ignored -> new byte[0];
            case WorldPlanOp.RemoveCooldown ignored -> new byte[0];
        };
    }

    /** 写入 Lua 的回复：1 已写入（新版本号即 {@link WorldPlanBatch#writtenVersion()}）、−1 不是领导者、−2 版本冲突。 */
    static WorldPlanWriteResult writeResult(Object raw, WorldPlanBatch batch) {
        long r = integer(raw, "计划写入");
        if (r == -1) {
            return WorldPlanWriteResult.FENCED;
        }
        if (r == -2) {
            return WorldPlanWriteResult.CONFLICT;
        }
        if (r != 1) {
            throw new IllegalStateException("计划写入返回值非法: " + r);
        }
        return WorldPlanWriteResult.written(batch.writtenVersion());
    }

    // ---------------------------------------------------------------- 读

    /** 版本号键的值：不存在为 0；不是非负十进制整数视为数据损坏。 */
    static long parseVersion(String key, Object raw) {
        if (raw == null) {
            return 0;
        }
        String s = text(raw);
        try {
            long v = Long.parseLong(s);
            if (v >= 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面
        }
        throw new IllegalStateException("计划版本号不是非负整数 key=" + key + " value=" + s);
    }

    /** 拉取 Lua 的回复 {@code {ver, ch 字段, ch 值, …}}。 */
    static WorldPlan parsePlan(int zoneId, Object raw) {
        List<?> reply = list(raw, "计划拉取");
        if (reply.isEmpty() || (reply.size() - 1) % 2 != 0) {
            throw new IllegalStateException("计划拉取回复形状非法 zone=" + Integer.toUnsignedString(zoneId) + " size=" + reply.size());
        }
        long version = parseVersion("ver", reply.get(0));
        Map<Long, WorldChannel> channels = parseChannels(zoneId, reply, 1, reply.size() - 1);
        List<WorldChannel> sorted = new ArrayList<>(channels.values());
        sorted.sort((a, b) -> Long.compareUnsigned(a.getSceneId(), b.getSceneId()));
        return new WorldPlan(version, sorted);
    }

    /** 快照 Lua 的回复 {@code {ver, now_ms, #ch, #desired, #cooldown, ch…, desired…, cooldown…}}（三段都是 HGETALL 的字段 / 值交替）。 */
    static WorldPlanSnapshot parseSnapshot(int zoneId, Object raw) {
        List<?> reply = list(raw, "计划快照");
        if (reply.size() < 5) {
            throw new IllegalStateException("计划快照回复形状非法 zone=" + Integer.toUnsignedString(zoneId) + " size=" + reply.size());
        }
        long version = parseVersion("ver", reply.get(0));
        long nowMs = integer(reply.get(1), "计划快照 TIME");
        int nCh = (int) integer(reply.get(2), "计划快照");
        int nDesired = (int) integer(reply.get(3), "计划快照");
        int nCooldown = (int) integer(reply.get(4), "计划快照");
        if (nCh < 0 || nDesired < 0 || nCooldown < 0 || nCh % 2 != 0 || nDesired % 2 != 0 || nCooldown % 2 != 0
                || 5L + nCh + nDesired + nCooldown != reply.size()) {
            throw new IllegalStateException("计划快照回复形状非法 zone=" + Integer.toUnsignedString(zoneId) + " size=" + reply.size()
                    + " ch=" + nCh + " desired=" + nDesired + " cooldown=" + nCooldown);
        }
        Map<Long, WorldChannel> channels = parseChannels(zoneId, reply, 5, nCh);
        Map<Integer, Integer> desired = new HashMap<>();
        int from = 5 + nCh;
        for (int i = from; i < from + nDesired; i += 2) {
            Integer conf = confField(zoneId, "desired", reply.get(i));
            if (conf == null) {
                continue;
            }
            String value = text(reply.get(i + 1));
            try {
                desired.put(conf, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                log.warn("期望频道数不是整数，跳过（按配置播种，HSETNX 不覆盖） zone={} conf={} value={}",
                        Integer.toUnsignedString(zoneId), Integer.toUnsignedString(conf), value);
            }
        }
        Map<Integer, Long> cooldown = new HashMap<>();
        from += nDesired;
        for (int i = from; i < from + nCooldown; i += 2) {
            Integer conf = confField(zoneId, "cooldown", reply.get(i));
            if (conf == null) {
                continue;
            }
            String value = text(reply.get(i + 1));
            try {
                cooldown.put(conf, Long.parseLong(value));
            } catch (NumberFormatException e) {
                log.warn("冷却到期时刻不是整数，按一直在冷却处理 zone={} conf={} value={}",
                        Integer.toUnsignedString(zoneId), Integer.toUnsignedString(conf), value);
                cooldown.put(conf, Long.MAX_VALUE);
            }
        }
        return new WorldPlanSnapshot(version, nowMs, channels, desired, cooldown);
    }

    /** 预占 Lua 的回复 {@code {best（从 1 起）, load}}。 */
    static ReservationPick parsePick(Object raw, int candidates) {
        List<?> reply = list(raw, "软预占");
        if (reply.size() != 2) {
            throw new IllegalStateException("软预占回复形状非法 size=" + reply.size());
        }
        long best = integer(reply.get(0), "软预占");
        if (best < 1 || best > candidates) {
            throw new IllegalStateException("软预占回复下标越界 best=" + best + " candidates=" + candidates);
        }
        return new ReservationPick((int) best - 1, integer(reply.get(1), "软预占"));
    }

    /** 计数 Lua 的回复：与键同序的整数数组。 */
    static List<Long> parseCounts(Object raw, int expected) {
        List<?> reply = list(raw, "预占计数");
        if (reply.size() != expected) {
            throw new IllegalStateException("预占计数回复长度非法 size=" + reply.size() + " expected=" + expected);
        }
        List<Long> out = new ArrayList<>(expected);
        for (Object v : reply) {
            out.add(integer(v, "预占计数"));
        }
        return out;
    }

    /**
     * 频道表段：字段必须是非 0 的无符号十进制、值必须解得开、且记录的 scene_id 与字段一致——任何一条不符都按数据损坏抛出
     * （不能「跳过坏的、照常规划 / 应用」：领导者会以为那个频道不存在而重铺，节点会把本地场景当孤儿排空）。
     */
    private static Map<Long, WorldChannel> parseChannels(int zoneId, List<?> reply, int from, int n) {
        Map<Long, WorldChannel> channels = new HashMap<>();
        for (int i = from; i < from + n; i += 2) {
            String field = text(reply.get(i));
            long sceneId;
            try {
                sceneId = Long.parseUnsignedLong(field);
            } catch (NumberFormatException e) {
                throw corrupt(zoneId, field, "字段不是无符号十进制", e);
            }
            WorldChannel channel;
            try {
                channel = WorldChannel.parseFrom(bytes(reply.get(i + 1)));
            } catch (InvalidProtocolBufferException e) {
                throw corrupt(zoneId, field, "记录解析失败", e);
            }
            if (sceneId == 0 || channel.getSceneId() != sceneId) {
                throw corrupt(zoneId, field, "记录的 scene_id 与字段不符: " + Long.toUnsignedString(channel.getSceneId()), null);
            }
            channels.put(sceneId, channel);
        }
        return channels;
    }

    private static IllegalStateException corrupt(int zoneId, String field, String what, Exception cause) {
        return new IllegalStateException("频道计划数据损坏 zone=" + Integer.toUnsignedString(zoneId) + " field=" + field + "：" + what
                + "（运维核对后 HDEL 这一条）", cause);
    }

    private static Integer confField(int zoneId, String table, Object raw) {
        String field = text(raw);
        try {
            return Integer.parseUnsignedInt(field);
        } catch (NumberFormatException e) {
            log.warn("频道{}表的字段不是 conf，跳过 zone={} field={}", table, Integer.toUnsignedString(zoneId), field);
            return null;
        }
    }

    // ---------------------------------------------------------------- 基本类型

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static List<?> list(Object raw, String what) {
        if (raw instanceof List<?> list) {
            return list;
        }
        throw new IllegalStateException(what + " 回复不是数组: " + describe(raw));
    }

    private static long integer(Object raw, String what) {
        if (raw instanceof Long n) {
            return n;
        }
        if (raw instanceof Integer n) {
            return n;
        }
        if (raw instanceof byte[] || raw instanceof String) {
            String s = text(raw);
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(what + " 回复不是整数: " + s, e);
            }
        }
        throw new IllegalStateException(what + " 回复不是整数: " + describe(raw));
    }

    private static String text(Object raw) {
        if (raw instanceof byte[] b) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof Long || raw instanceof Integer) {
            return raw.toString();
        }
        throw new IllegalStateException("回复元素不是字符串: " + describe(raw));
    }

    private static byte[] bytes(Object raw) {
        if (raw instanceof byte[] b) {
            return b;
        }
        if (raw instanceof String s) {
            return s.getBytes(StandardCharsets.ISO_8859_1);
        }
        throw new IllegalStateException("回复元素不是字节串: " + describe(raw));
    }

    private static String describe(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }
}
