package com.game.battle.port;

import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;

/**
 * battle → scene 的结算出站端口（基线 {@code DispatchSettlementDurably}，{@code room.cpp:138-149}、{@code :248-255}；battle-node-spec §4.6、§7.9；
 * scene-battle-spec §7.15）。生产实现是 6.3 的结算发件箱（{@code SettlementOutbox}：先落 Redis 待结算记录、后投递、未销账就有界重投）；
 * {@link LoggingSettlementSink} 只记日志，留给不接传输的测试装配。
 *
 * <p>契约：
 * <ul>
 *   <li><b>线程</b>：只在逻辑线程（{@code battle-logic}）上调用；实现必须<b>异步、不阻塞、不抛异常</b>——发件箱只把任务交给自己的
 *       {@code battle-outbox} 线程，落库、定位、投递、重投都在那边。持久性由实现负责。</li>
 *   <li><b>调用点</b>：{@code FinishBattle} 按 player_id 升序逐名参战者「推本人 150 → 调本端口」交替进行（R6）；正常收尾与整场期限（DRAW）都调，
 *       Destroy / 停机作废不调。房间对同一 (battle_id, player_id) 只调一次；实现<b>可以被重复调用</b>而不重复发奖
 *       （落库按局幂等；这一局已销账时落库被已销账墓碑挡下，不再投递）。</li>
 *   <li>{@code RoomOrigin.DEV} 的房间<b>不调</b>本端口（房间只记日志），dev 接口不能变成发奖口子；{@code DEV_GATHER} 的房间照调
 *       （快照来自 scene）。</li>
 *   <li><b>寻址</b>（D14）：首投与重投都按玩家此刻的位置记录重新定位持有者节点，{@code target_instance_id} 取节点目录里的实例；
 *       <b>不</b>按快照路由投递。</li>
 * </ul>
 */
public interface SettlementSink {

    /**
     * @param routing    该玩家快照里的路由（<b>只进日志</b>：开局时的 scene 节点号；投递目标另行定位，不得修改）
     * @param settlement 本人那份结算（outcome 已被节点覆盖，battle_id 已补上）
     */
    void dispatch(BattleRouting routing, long playerId, BattleSettlementData settlement);
}
