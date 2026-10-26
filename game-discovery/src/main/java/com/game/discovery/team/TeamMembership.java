package com.game.discovery.team;

import com.game.discovery.proto.TeamInfo;

/**
 * 一个玩家此刻的组队成员关系：{@link TeamMembershipReader#readAsync} 的一致性快照。
 * 生产从同一次 SQL 快照读取永久成员关系与队伍原件；旧 Redis 兼容构造器从同一次 Lua 读取索引与投影。
 *
 * <p>三态对应基线 C++ {@code TeamIndexReplyKind}（cpp/libs/services/scene/player/system/player_team.h:38-45）：
 * 索引两个字段都缺失 = 键缺失（{@code keyMissing}，按无队处理）；两个字段都是合法十进制 = 存在（tid 可以为 0：已离队但保留 epoch）；
 * 其余形状（半个 hash、非十进制）= 未知——Java 不构造本类，{@code readAsync} 异常完成。
 *
 * @param teamId     所在队（无符号）；0 = 无队（索引 tid 为 {@code "0"}，或索引键缺失）
 * @param epoch      成员关系版本（无符号，只在 tid 变化时变）；索引键缺失时为 0
 * @param keyMissing 无永久成员行；兼容 Redis 路径也用于索引键缺失
 * @param info       SQL 队伍原件中的投影（兼容路径来自 Redis）；{@code teamId = 0} 时恒为 null，旧缓存投影缺失时也可为 null。
 *                   调用方仍核对 team_id / members / leader_id 以及成员 epoch，不将未知覆盖成无队。
 */
public record TeamMembership(long teamId, long epoch, boolean keyMissing, TeamInfo info) {

    /** 索引键缺失（无队）。 */
    public static final TeamMembership KEY_MISSING = new TeamMembership(0, 0, true, null);

    public TeamMembership {
        if (keyMissing && (teamId != 0 || epoch != 0 || info != null)) {
            throw new IllegalArgumentException("索引键缺失时不应有 tid / epoch / 投影");
        }
        if (teamId == 0 && info != null) {
            throw new IllegalArgumentException("无队时不应有投影");
        }
    }

    /** 在队（tid ≠ 0）。 */
    public boolean inTeam() {
        return teamId != 0;
    }

    /** 在队但投影缺失（game-team 下一次提交 / 续期会重写；读者按「不跟随」处理，team-spec §6.10）。 */
    public boolean projectionMissing() {
        return teamId != 0 && info == null;
    }
}
