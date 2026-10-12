package com.game.battle.push;

/**
 * battle 下行出口的路由判定（基线 {@code cpp/nodes/battle/battle_push_policy.h Decide}；battle-node-spec §5.7）。纯函数，不打日志、不做 I/O。
 *
 * <p>直连是战斗帧的唯一通路：有活直连一律直发（大厅公告也一样，客户端已经在直连上了，不必再绕 gate）；没有活直连时，
 * 只有大厅公告回落 gate，其余（战斗帧，以及任何将来新增而没在这里显式放行的类别）一律丢弃——回落是白名单，不是默认。
 */
public final class PushPolicy {

    private PushPolicy() {
    }

    /**
     * @param hasLiveDirect 该玩家在房间里有一条已验证、仍连接、未进入关闭流程的直连（{@code DirectLink#isLive()}）
     */
    public static PushRoute decide(PushCategory category, boolean hasLiveDirect) {
        if (hasLiveDirect) {
            return PushRoute.DIRECT;
        }
        return category == PushCategory.LOBBY_ANNOUNCEMENT ? PushRoute.VIA_GATE : PushRoute.DROP;
    }
}
