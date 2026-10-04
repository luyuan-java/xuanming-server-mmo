package com.game.team.rules;

import java.util.Map;

/**
 * 规则层看到的成员会话状态（基线 rules.go:37-50）。只用于决策，不写进记录。
 *
 * <p>Java 版由 {@code xm:presence} 与 {@code xm:location.s} 组合得出（team-spec §6.6，D3）：
 * presence 正常 → {@link #ONLINE}；否则 location 的 s 为 o / l → {@link #PRESENT}；location 缺失或 s 为 x → {@link #ABSENT}；
 * 任何读失败、条目损坏、身份不符 → {@link #UNKNOWN}（逐成员 fail-closed，不让 RPC 失败）。
 */
public enum SessionState {

    /** 未查询或读失败（map 缺项即此值）：既不算在线，也不据此惰性转让队长（fail-closed）。 */
    UNKNOWN,
    /** 会话不存在（正常登出或租约到期）：<b>唯一</b>触发惰性转让队长的状态。 */
    ABSENT,
    /** 会话存在但不是在线（如断线重连宽限）：不算在线，也不算离线。 */
    PRESENT,
    /** 在线。 */
    ONLINE;

    /**
     * 取某个玩家的状态。{@code sessions} 为 null 或缺项时都是 {@link #UNKNOWN}（基线 store.go:69-73：
     * sessions 为 nil 时全员 Unknown）。
     */
    public static SessionState of(Map<Long, SessionState> sessions, long playerId) {
        if (sessions == null) {
            return UNKNOWN;
        }
        SessionState state = sessions.get(playerId);
        return state == null ? UNKNOWN : state;
    }
}
