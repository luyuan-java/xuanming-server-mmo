package com.game.team.match;

/**
 * 批次 4.3 的开战端口：没有任何副本开放组队（team-spec §5.4，D11）。
 *
 * <p>StartTeamMatch 保留基线第 1–3 步的真实逻辑（刷新 / 惰性转让、队长与锁校验、副本人数），人数这一步恒回 4027 kTeamDungeonNotOpen
 * + 同源视图（IDLE）——正是基线「该副本未配置组队人数」的路径（team_battle_test.go:240）。不用 4030（基线 starter 为 nil 的分支）：
 * 那是故障，会触发告警、文案「服务器繁忙」也误导玩家。4.3 不开放组队人数的配置项，免得配了之后走进尚未实现的预检 / 加锁路径。
 */
public final class NoTeamBattle implements TeamBattlePort {

    public static final NoTeamBattle INSTANCE = new NoTeamBattle();

    private NoTeamBattle() {
    }

    @Override
    public int teamSizeFor(int battleConfigId) {
        return 0;
    }
}
