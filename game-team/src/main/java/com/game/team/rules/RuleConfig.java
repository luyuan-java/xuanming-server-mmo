package com.game.team.rules;

import com.game.common.team.TeamPolicyContract;

/**
 * 规则层配置（基线 rules.go:55-59）。
 *
 * @param allowCrossZone  是否允许不同 home zone 的玩家同队（{@code game.team.allow-cross-zone}，缺省 false）。
 *                        从 true 改回 false 只拦新增，已有的跨区成员保留（rules_test.go:193 TestCrossZoneSwitch）
 * @param pvpRatingGapMax 计分组排整队开战的成员评分极差上限（分；缺省 500，0 = 不限；纳入全局策略指纹）
 */
public record RuleConfig(boolean allowCrossZone, int pvpRatingGapMax) {

    public RuleConfig {
        if (pvpRatingGapMax < 0) {
            throw new IllegalArgumentException("pvpRatingGapMax 不能为负: " + pvpRatingGapMax);
        }
    }

    public RuleConfig(boolean allowCrossZone) {
        this(allowCrossZone, TeamPolicyContract.DEFAULT_PVP_RATING_GAP_MAX);
    }

    /** 缺省配置：不允许跨区、评分差上限 500 分。 */
    public static final RuleConfig DEFAULT = new RuleConfig(false, TeamPolicyContract.DEFAULT_PVP_RATING_GAP_MAX);
}
