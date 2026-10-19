package com.game.data.tools.playerbundle;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.proto.BattleSettlementEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排查一名玩家要看的 Redis 键（全部经 {@link RedisKeys} 生成，不手拼键名）以及它们的解码方式。
 *
 * <p>只列「一把键就是这名玩家的」键。没列的：登录令牌族（键名里就是令牌明文，<b>绝不读</b>）、以账号为键的设备表、需要 SCAN 才找得到的
 * 战斗已销账墓碑 {@code battle:{pid}:settled:<battle_id>}、共享集合里的成员（世界频道预约、匹配队列与评分镜像）、好友列表缓存
 * （MySQL 的缓存，看库即可）——使用手册写明。
 */
final class PlayerRedisKeys {

    /** 一把键：键名、用途说明、string 值或 hash 字段值的解码器（null = 按 UTF-8 文本给出）。 */
    record Spec(String key, String purpose, Decoder stringDecoder, Map<String, Decoder> hashDecoders, Decoder hashValueDecoder) {
    }

    /** protobuf 解码器（解出 JSON 文本供查看）。 */
    interface Decoder {
        Message parse(byte[] bytes) throws InvalidProtocolBufferException;
    }

    private PlayerRedisKeys() {
    }

    static List<Spec> specs(RedisKeys keys, long playerId) {
        return List.of(
                new Spec(keys.playerLocation(playerId),
                        "位置记录（hash e=归属 epoch、q=写序号、s=o 在线 / l 断线重连租约或待落点 / x 登出墓碑、v=PlayerLocation）：login 进游戏据它定去向",
                        null, Map.of("v", PlayerLocation::parseFrom), null),
                new Spec(keys.presence(playerId),
                        "在线目录（PlayerPresence：所在 gate、会话、进游戏时刻与 epoch，TTL 60 s，gate 写）：推送与好友在线状态据它",
                        PlayerPresence::parseFrom, null, null),
                new Spec(keys.battleLock(playerId),
                        "战斗锁（hash b=battle_id、n=节点、s=P 备战 / F 战斗中、d / p=期限）：存在 = 处于备战或战斗，进场会恢复冻结",
                        null, null, null),
                new Spec(keys.battleSettlements(playerId),
                        "待结算战斗奖励（hash 字段=battle_id，值=BattleSettlementEvent，7 天）：battle 已落、scene 还没应用并销账的奖励（资产）",
                        null, null, BattleSettlementEvent::parseFrom),
                new Spec(keys.teamPlayer(playerId), "组队归属（hash tid=队伍号、epoch）", null, null, null),
                new Spec(keys.teamInvite(playerId), "待处理的组队邀请（zset 成员=队伍号，分数=到期时刻）", null, null, null),
                new Spec(keys.guildOfPlayer(playerId), "玩家所属帮会缓存（0 = 无帮会也缓存）", null, null, null),
                new Spec(keys.matchTicket(playerId), "匹配票据（hash：排队 / 已就绪、队列键、对局号等）", null, null, null),
                new Spec(keys.matchChallengeTarget(playerId), "待处理的切磋邀请（值=切磋号，60 s）", null, null, null),
                new Spec(keys.matchWatching(playerId), "观战标记（值=<battle_id>:<nonce>，360 s）", null, null, null));
    }

    /**
     * 导入第二段要清掉的本地键：login 进游戏读的位置记录（别的 zone 的记录会把登录重定向走、本 zone 的会试图回到不存在的实例），
     * 以及 scene 进场后读的战斗锁与待结算奖励（残留的锁会把玩家冻在不存在的战斗里，残留的奖励会被应用到导入的数据上、污染复现）。
     */
    static List<String> importCleanup(RedisKeys keys, long playerId) {
        return List.of(keys.playerLocation(playerId), keys.battleLock(playerId), keys.battleSettlements(playerId));
    }

    /** 原始读数 → 导出包条目（能解码的给出 JSON，解不了的给出原字节 base64）。 */
    static PlayerBundle.RedisEntry render(Spec spec, PlayerRedis.RawEntry raw) {
        if (raw.error() != null) {
            return new PlayerBundle.RedisEntry(spec.key(), spec.purpose(), null, null, null, null, null, null, raw.error());
        }
        String value = null;
        String valueBase64 = null;
        Map<String, String> hash = null;
        Map<String, Double> zset = null;
        if (raw.string() != null) {
            if (spec.stringDecoder() != null) {
                value = decodeProto(spec.stringDecoder(), raw.string());
            } else {
                value = utf8OrNull(raw.string());
            }
            valueBase64 = Base64.getEncoder().encodeToString(raw.string());
        } else if (raw.hash() != null) {
            hash = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> field : raw.hash().entrySet()) {
                Decoder decoder = spec.hashDecoders() != null ? spec.hashDecoders().get(field.getKey()) : null;
                if (decoder == null) {
                    decoder = spec.hashValueDecoder();
                }
                hash.put(field.getKey(), decoder != null ? decodeProto(decoder, field.getValue()) : utf8OrBase64(field.getValue()));
            }
        } else if (raw.zset() != null) {
            zset = new LinkedHashMap<>();
            for (PlayerRedis.ZMember member : raw.zset()) {
                zset.put(member.member(), member.score());
            }
        }
        return new PlayerBundle.RedisEntry(spec.key(), spec.purpose(), raw.type(), raw.pttlMs(), value, valueBase64, hash, zset,
                null);
    }

    /** 解成 JSON 文本；解不了时回 {@code "!parse-error: …; base64=…"}，不丢原字节。 */
    static String decodeProto(Decoder decoder, byte[] bytes) {
        try {
            return JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace().print(decoder.parse(bytes));
        } catch (InvalidProtocolBufferException e) {
            return "!parse-error: " + e.getMessage() + "; base64=" + Base64.getEncoder().encodeToString(bytes);
        }
    }

    private static String utf8OrBase64(byte[] bytes) {
        String text = utf8OrNull(bytes);
        return text != null ? text : "base64:" + Base64.getEncoder().encodeToString(bytes);
    }

    /** 严格 UTF-8 且没有控制字符时回文本，否则 null。 */
    static String utf8OrNull(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.chars().anyMatch(c -> c < 0x20 && c != '\t' && c != '\n' && c != '\r') ? null : text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
