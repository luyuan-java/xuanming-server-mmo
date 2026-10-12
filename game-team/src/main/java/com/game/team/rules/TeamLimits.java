package com.game.team.rules;

/**
 * 组队规则常量（基线 go/match/internal/team/rules.go:20-35，team-spec §0.4）。写死在代码里，不做成配置。
 *
 * <p>存储层的 TTL、重试次数等常量不在这里（归存储层，基线 store.go:31-38）。
 */
public final class TeamLimits {

    /**
     * 队伍容量。必须等于引擎每队上限 {@code kMaxBattleTeamSize}（基线 rules.go:21-23；两边各自用测试钉住字面量 5，
     * team_battle_test.go:179 TestMatchLockRules）。满员的判定是 {@code 成员数 >= CAPACITY}（rules.go:441）。
     */
    public static final int CAPACITY = 5;

    /** 申请有效期（毫秒，Redis TIME 口径；rules.go:26）。 */
    public static final long APPLICATION_TTL_MS = 120_000L;

    /** 每队申请上限；超限时淘汰最早一条、本次刚加的永不淘汰，不报错（rules.go:27、:978-1002）。 */
    public static final int MAX_APPLICATIONS = 10;

    /** 邀请有效期（毫秒；rules.go:30）。 */
    public static final long INVITE_TTL_MS = 60_000L;

    /** 每队邀请上限；超限时淘汰最早一条、本次的 target 永不淘汰（rules.go:31、:1004-1028）。 */
    public static final int MAX_INVITES_PER_TEAM = 10;

    /**
     * 每个被邀请人的待处理邀请上限。规则层不判，只在提交脚本里原子判定（超限回 {@code {-3,i}} → 4022），
     * 作为 ARGV 最后一项传入（基线 rules.go:33-34、store.go:847；已知可超出 1 条，见 team-spec §8.1 第 1 条，照搬基线）。
     */
    public static final int MAX_PENDING_INVITES_PER_INVITEE = 10;

    private TeamLimits() {
    }
}
