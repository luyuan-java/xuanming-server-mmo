package com.game.discovery.team;

/**
 * 组队 Redis hash 的字段名：写者 xm-team（Lua）与读者 xm-scene（{@link TeamMembershipReader}）之间的进程间契约，
 * 改名必须两边同改（team-spec §1.3「字段名共享」、§6.5；基线 go/match/internal/team/keys.go:25-35，
 * C++ scene 按同名 {@code HMGET tid epoch}，cpp/libs/services/scene/player/system/player_team.cpp:37、:194）。
 * 键名经 {@code RedisKeys.teamRecord / teamPlayer} 生成。
 */
public final class TeamRedisFields {

    /** 记录 {@code xm:{team}:rec:<tid>} 的版本号：十进制，首次为 1，每次提交严格 +1（team-spec §1.5）。 */
    public static final String VER = "ver";
    /** 记录 {@code xm:{team}:rec:<tid>} 的 TeamRecord 字节（任意字节，经 ByteArrayCodec 读写）。 */
    public static final String PB = "pb";
    /** 玩家索引 {@code xm:{team}:player:<pid>} 的所在队：十进制无符号，无队为 {@code "0"}（离队不删键，只置 0）。 */
    public static final String TID = "tid";
    /** 玩家索引 {@code xm:{team}:player:<pid>} 的成员关系版本：十进制无符号，只在 tid 变化时变（team-spec §1.4）。 */
    public static final String EPOCH = "epoch";

    private TeamRedisFields() {
    }
}
