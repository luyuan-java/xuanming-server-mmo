package com.game.team.rules;

/**
 * 规则层配置（基线 rules.go:55-59）。
 *
 * @param allowCrossZone 是否允许不同 home zone 的玩家同队（{@code xm.team.allow-cross-zone}，缺省 false）。
 *                       从 true 改回 false 只拦新增，已有的跨区成员保留（rules_test.go:193 TestCrossZoneSwitch）
 */
public record RuleConfig(boolean allowCrossZone) {

    /** 缺省配置：不允许跨区。 */
    public static final RuleConfig DEFAULT = new RuleConfig(false);
}
