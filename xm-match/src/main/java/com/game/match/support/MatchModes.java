package com.game.match.support;

import com.game.proto.match.MatchMode;

/**
 * 匹配模式的固定规则（纯函数；基线 {@code join.go:64-93}、{@code matcher.go:361-378}、{@code rating.go:94-101}、{@code gather.go:36-40}）。
 * 排队入口（JoinQueue）与凑单（matcher）必须用同一个口径算「这条队列凑几个人」，所以放在这里而不是各写一份。
 *
 * <p>入参一律是 {@code MatchMode} 的<b>数值</b>：客户端可以发来契约里没有的值，队列键里存的也是数值。
 */
public final class MatchModes {

    public static final int UNSPECIFIED = MatchMode.MATCH_MODE_UNSPECIFIED_VALUE;
    public static final int FIVE_V_FIVE = MatchMode.MATCH_MODE_5V5_VALUE;
    public static final int THREE_V_THREE = MatchMode.MATCH_MODE_3V3_VALUE;
    public static final int ONE_V_ONE = MatchMode.MATCH_MODE_1V1_VALUE;
    public static final int PVE_SOLO = MatchMode.MATCH_MODE_PVE_SOLO_VALUE;
    public static final int PVE_TEAM = MatchMode.MATCH_MODE_PVE_TEAM_VALUE;
    public static final int PVP_CHALLENGE = MatchMode.MATCH_MODE_PVP_CHALLENGE_VALUE;

    /** 5V5 一局的人数。 */
    public static final int FIVE_V_FIVE_PLAYERS = 10;
    /** 1V1 / 切磋一局的人数。 */
    public static final int ONE_V_ONE_PLAYERS = 2;

    private MatchModes() {
    }

    /**
     * 排队入口对这个模式开不开放：只有 5V5、1V1、PVE_SOLO、PVE_TEAM。3V3、PVP_CHALLENGE（只能经切磋入口）、UNSPECIFIED 与契约里没有的值都不开放
     * （157 回 16002「该匹配模式未开放」）。PVE_TEAM 另要看该副本配没配组队人数（没配回 16003，判定先于 16002）。
     */
    public static boolean joinable(int mode) {
        return mode == FIVE_V_FIVE || mode == ONE_V_ONE || mode == PVE_SOLO || mode == PVE_TEAM;
    }

    /**
     * 这个模式凑满一局要几个人：PVE_SOLO = 1、1V1 = 2、5V5 = 10、PVE_TEAM = {@code pveTeamSize}（调用方传该副本的组队人数，已按 5 收口，
     * 未配置为 0）；其余模式为 0。返回 0 = 不能排队 / 凑单遇到这样的队列只告警跳过、不动数据。
     */
    public static int requiredPlayers(int mode, int pveTeamSize) {
        if (mode == PVE_SOLO) {
            return 1;
        }
        if (mode == ONE_V_ONE) {
            return ONE_V_ONE_PLAYERS;
        }
        if (mode == FIVE_V_FIVE) {
            return FIVE_V_FIVE_PLAYERS;
        }
        if (mode == PVE_TEAM) {
            return Math.max(0, pveTeamSize);
        }
        return 0;
    }

    /** 评分模式（只有 1V1 与 5V5）：凑单看评分容差、对局结果入账 Elo。PVE、切磋、活动一律不计分。 */
    public static boolean rated(int mode) {
        return mode == ONE_V_ONE || mode == FIVE_V_FIVE;
    }

    /** 走队列凑单的模式（1V1、5V5、PVE_TEAM）。PVE_SOLO 不入队（建票即 matched、立即 gather）。 */
    public static boolean queued(int mode) {
        return mode == ONE_V_ONE || mode == FIVE_V_FIVE || mode == PVE_TEAM;
    }
}
