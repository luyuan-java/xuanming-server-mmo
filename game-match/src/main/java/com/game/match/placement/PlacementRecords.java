package com.game.match.placement;

import com.game.match.proto.BattlePlacement;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;

/**
 * 落点记录（Redis HASH {@code RedisKeys.matchBattlePlacement(battle_id)}）的<b>存储形状</b>：字段名与「读回来的字段算不算一条好记录」的判据。
 * 这条 HASH 有两个读写方——{@link RedissonPlacementStore}（gather 写、179 读）与 6.5 的观战存储（{@code spectate} 包：原子读快照、
 * 按 attempt 守护的剔除与公开都在自己的 Lua 里碰同一条 HASH）——字段名与损坏判据放在这里，两边不各写一份。
 *
 * <p>字段：{@value #FIELD_ATTEMPT} = attempt 的无符号十进制 ASCII（给 Lua 做单调比较与 attempt 守护用）；{@value #FIELD_PLACEMENT} =
 * {@link BattlePlacement} 的字节。正常写者（{@code RedissonPlacementStore} 的写脚本）总是一次 HSET 同时写两个字段，
 * 且 {@value #FIELD_ATTEMPT} 恒等于消息里的 {@code attempt}。Lua 脚本里只能写字面量，脚本的单测负责核对字面量与这里的常量一致。
 *
 * <p>纯函数，线程安全。
 */
public final class PlacementRecords {

    /** HASH 字段：attempt（无符号十进制 ASCII）。 */
    public static final String FIELD_ATTEMPT = "a";
    /** HASH 字段：{@link BattlePlacement} 的字节。 */
    public static final String FIELD_PLACEMENT = "pb";

    private PlacementRecords() {
    }

    /** 解析的结果（二选一，调用方穷举）。「键不存在」不在这里：那由调用方在解析之前自己判。 */
    public sealed interface Parsed {

        /** 一条好记录。 */
        record Ok(BattlePlacement placement) implements Parsed {
        }

        /** HASH 在，但不是一条好记录（正常写者不会产生）。{@code why} 只进日志。 */
        record Corrupt(String why) implements Parsed {
        }
    }

    /**
     * 只看 {@value #FIELD_PLACEMENT} 字段（179 补签 / {@link PlacementStore#read} 的口径，与 6.4 相同）。三种情况算损坏：
     * 字段缺失（null 或 0 字节）；字节解析不成 {@link BattlePlacement}；消息里的 {@code battle_id} 与键不符。
     *
     * @param battleId 键里的 battle_id
     * @param pb       {@value #FIELD_PLACEMENT} 字段的字节（缺失传 null 或空数组）
     */
    public static Parsed parse(long battleId, byte[] pb) {
        if (pb == null || pb.length == 0) {
            return new Parsed.Corrupt("落点记录缺 " + FIELD_PLACEMENT + " 字段");
        }
        BattlePlacement placement;
        try {
            placement = BattlePlacement.parseFrom(pb);
        } catch (InvalidProtocolBufferException e) {
            return new Parsed.Corrupt("落点记录解析失败: " + e.getMessage());
        }
        if (placement.getBattleId() != battleId) {
            return new Parsed.Corrupt("落点记录与键不符 recorded=" + Long.toUnsignedString(placement.getBattleId()));
        }
        return new Parsed.Ok(placement);
    }

    /**
     * 两个字段都看（6.5 观战存储的口径）：在 {@link #parse(long, byte[])} 的三条之外，{@value #FIELD_ATTEMPT} 字段缺失、
     * 或它不等于消息里 {@code attempt} 的无符号十进制，也算损坏。
     *
     * <p>为什么观战要多这一条：它拿读到的 {@code placement.attempt} 去做「按 attempt 守护的剔除」与「attempt 一致才公开」，
     * 而 Lua 那一侧比的是 {@value #FIELD_ATTEMPT} 字段——两者不一致的记录，守护会永远不成立或错误成立。把它读成损坏
     * （163 回 16004、列表跳过，都不剔除），调用方就可以放心地认为「读到的 attempt = HASH 里的 attempt」。179 不用 attempt，所以不核对。
     *
     * @param battleId 键里的 battle_id
     * @param attempt  {@value #FIELD_ATTEMPT} 字段的字节（缺失传 null 或空数组）
     * @param pb       {@value #FIELD_PLACEMENT} 字段的字节（缺失传 null 或空数组）
     */
    public static Parsed parse(long battleId, byte[] attempt, byte[] pb) {
        Parsed parsed = parse(battleId, pb);
        if (!(parsed instanceof Parsed.Ok ok)) {
            return parsed;
        }
        if (attempt == null || attempt.length == 0) {
            return new Parsed.Corrupt("落点记录缺 " + FIELD_ATTEMPT + " 字段");
        }
        String recorded = new String(attempt, StandardCharsets.UTF_8);
        String expected = attemptField(ok.placement().getAttempt());
        if (!recorded.equals(expected)) {
            return new Parsed.Corrupt("落点记录的 " + FIELD_ATTEMPT + " 字段与消息里的 attempt 不符 " + FIELD_ATTEMPT + "='" + recorded + "' attempt=" + expected);
        }
        return parsed;
    }

    /** {@value #FIELD_ATTEMPT} 字段的写法（也是传给 Lua 做 attempt 比较的 ARGV 的写法）：{@code attempt} 的无符号十进制。 */
    public static String attemptField(int attempt) {
        return Integer.toUnsignedString(attempt);
    }
}
