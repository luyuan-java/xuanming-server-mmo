package com.game.team.match;

/**
 * 整队开战的票据域端口（基线 go/match/internal/team/service.go:40-60 BattleStarter，team-spec §5.3）。
 *
 * <p>批次 4.3 只用到 {@link #teamSizeFor}，实现是 {@link NoTeamBattle}（没有任何副本开放组队，StartTeamMatch 走规则路径回 4027，D11）。
 * 批次 6.4 接入真端口时补齐基线的其余四个方法（MatchLockTTLSeconds / TicketBlocked / CreateMatchedTickets / RunGather），
 * 跨进程时做成 xm-match 提供的 Dubbo 接口；StartTeamMatch 的入口代码不用改。
 */
@FunctionalInterface
public interface TeamBattlePort {

    /**
     * 副本组队开战人数上限（与 PVE_TEAM 排队同口径，已按引擎每队上限 5 收口）；0 = 该副本未开放组队（→ 4027）。
     *
     * @param battleConfigId DungeonTable id（uint32 位模式）
     * @return uint32 位模式
     */
    int teamSizeFor(int battleConfigId);
}
