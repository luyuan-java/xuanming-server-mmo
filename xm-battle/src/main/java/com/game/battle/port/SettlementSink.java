package com.game.battle.port;

import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;

/**
 * battle → scene 的结算出站端口（基线 {@code DispatchSettlementDurably}，{@code room.cpp:138-149}、{@code :248-255}；battle-node-spec §4.6、§7.9）。
 * 6.2 的缺省实现 {@link LoggingSettlementSink} 只记日志；真实传输由 6.3 接入（先落 Redis 待结算记录、后投递、未销账就重投）。
 *
 * <p>契约：
 * <ul>
 *   <li>只在逻辑线程上调用；实现必须<b>异步、不阻塞、不抛异常</b>，持久性由实现负责（调用方只调一次）。</li>
 *   <li>调用点：{@code FinishBattle} 按 player_id 升序逐名参战者「推本人 150 → 调本端口」交替进行（R6）；正常收尾与整场期限（DRAW）都调，
 *       Destroy / 停机作废不调。</li>
 *   <li>{@code RoomOrigin.DEV} 的房间<b>不调</b>本端口（房间只记日志），dev 接口不能变成发奖口子。</li>
 *   <li>重投时按玩家位置重新解析目标节点（同基线 {@code room.cpp:138-149}），不死守快照路由。</li>
 * </ul>
 */
public interface SettlementSink {

    /**
     * @param routing    该玩家快照里的路由（首投的目标；不得修改）
     * @param settlement 本人那份结算（outcome 已被节点覆盖，battle_id 已补上）
     */
    void dispatch(BattleRouting routing, long playerId, BattleSettlementData settlement);
}
