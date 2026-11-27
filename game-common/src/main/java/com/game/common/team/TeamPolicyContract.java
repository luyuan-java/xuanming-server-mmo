package com.game.common.team;

import com.game.proto.match.MatchMode;

/** Team规则与Match全局准入指纹共用的实际硬约束；不含连接地址或线程池等实例配置。 */
public final class TeamPolicyContract {
    public static final int CAPACITY=5;
    public static final long APPLICATION_TTL_MS=120_000L;
    public static final int MAX_APPLICATIONS=10;
    public static final long INVITE_TTL_MS=60_000L;
    public static final int MAX_INVITES_PER_TEAM=10;
    public static final int MAX_PENDING_INVITES_PER_INVITEE=10;
    /** 计分组排成员评分极差默认上限（分；0 = 不限；乘 100 对应 centi）。 */
    public static final int DEFAULT_PVP_RATING_GAP_MAX=500;
    /** 缺少 match_rating 行的新手默认评分（centi，对应 1500.00 分）。 */
    public static final long DEFAULT_RATING_CENTI=150_000L;
    private TeamPolicyContract(){}
    public static String hardRules(){return "team-v1|"+CAPACITY+"|"+APPLICATION_TTL_MS+"|"+MAX_APPLICATIONS+"|"+INVITE_TTL_MS+"|"+MAX_INVITES_PER_TEAM+"|"+MAX_PENDING_INVITES_PER_INVITEE;}

    /** 每侧 5 人的 PVP_TEAM 池拒绝 4 人队（4 人队可打每侧 4 人的池）。 */
    public static boolean isDisallowedPvpPartySize(int matchMode, int rosterSize, int targetTeamSize) {
        if (matchMode != MatchMode.MATCH_MODE_PVP_TEAM_VALUE) {
            return false;
        }
        int effectiveTarget = targetTeamSize == 0 ? CAPACITY : targetTeamSize;
        return rosterSize == 4 && effectiveTarget == 5;
    }

    /** 是否属于需要校验队内评分差的计分组排模式。 */
    public static boolean isScoredPvpTeamMode(int matchMode) {
        return matchMode == MatchMode.MATCH_MODE_PVP_TEAM_VALUE
                || matchMode == MatchMode.MATCH_MODE_5V5_VALUE;
    }

    /** 队内最高与最低评分之差是否超过上限（maxGapPoints == 0 表示不限）。 */
    public static boolean exceedsRatingGap(int maxGapPoints, long minRatingCenti, long maxRatingCenti) {
        return maxGapPoints > 0 && (maxRatingCenti - minRatingCenti) > maxGapPoints * 100L;
    }
}
