package com.game.discovery.team;

import com.game.discovery.proto.TeamInfo;

/**
 * 一个玩家此刻的组队成员关系：{@link TeamMembershipReader#readAsync} 一次原子读的结果（索引与投影出自同一次 Lua，互相一致）。
 *
 * <p>三态对应基线 C++ {@code TeamIndexReplyKind}（cpp/libs/services/scene/player/system/player_team.h:38-45）：
 * 索引两个字段都缺失 = 键缺失（{@code keyMissing}，按无队处理）；两个字段都是合法十进制 = 存在（tid 可以为 0：已离队但保留 epoch）；
 * 其余形状（半个 hash、非十进制）= 未知——Java 不构造本类，{@code readAsync} 异常完成。
 *
 * @param teamId     所在队（无符号）；0 = 无队（索引 tid 为 {@code "0"}，或索引键缺失）
 * @param epoch      成员关系版本（无符号，只在 tid 变化时变）；索引键缺失时为 0
 * @param keyMissing 索引键不存在（过期或被淘汰）
 * @param info       队伍投影 {@code xm:{team}:info:<tid>}；{@code teamId = 0} 时恒为 null，{@code teamId ≠ 0} 而投影缺失时也为 null。
 *                   投影里的 team_id / members / leader_id 是否与索引自洽由调用方核对（基线 player_team.cpp:290-318）
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

    /** 在队但投影缺失（xm-team 下一次提交 / 续期会重写；读者按「不跟随」处理，team-spec §6.10）。 */
    public boolean projectionMissing() {
        return teamId != 0 && info == null;
    }
}
