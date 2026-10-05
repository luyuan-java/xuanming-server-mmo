package com.game.battle.port;

import com.game.proto.BattleRouting;

/**
 * battle → scene 的确认事件出站端口（基线 Kafka scene-cmd 上的 {@code BattleConfirmedEvent}，{@code room.cpp:150-197}、{@code :270-281}；
 * battle-node-spec §4.8、§7.9）。6.2 的缺省实现 {@link LoggingSceneBattleEvents} 只记日志；真实传输由 6.3 接入（推荐见 Q13）。
 *
 * <p>契约：
 * <ul>
 *   <li>只在逻辑线程上调用；实现必须<b>异步、不阻塞、不抛异常</b>（失败只计数、打日志，由下一次补发覆盖）。</li>
 *   <li>调用点：建房时按 player_id 升序对每名参战者首发一次（排在该玩家的 177 / 143 之后），之后每 10 s 补发，共 17 次
 *       （首发 + 补发每人最多 18 条）；战斗先结束时随收尾停发。dev 房间照常调用。</li>
 *   <li>地址解析（6.3 实现时遵守）：按快照路由的 {@code (zone_id, scene_node_id)} 查 scene 目录，目录里的 {@code instance_id} 必须等于
 *       {@code routing.scene_instance_id}，不等就不发、只计数（{@code skipped}），等价于基线 Kafka 的 {@code target_instance_id} 过滤；
 *       实例为空或节点为 0 也不发。</li>
 * </ul>
 */
public interface SceneBattleEvents {

    /**
     * 通知 scene：玩家进入正式战斗（scene 据此 PREPARING → FIGHTING，把作废期限切到 {@code deadlineMs}，并给锁续期；其余情况幂等）。
     *
     * @param routing    该玩家快照里的路由（不得修改）
     * @param deadlineMs 房间期限（= 票据 expire_at_ms，四者同值，§10.4）
     */
    void confirm(BattleRouting routing, long playerId, long battleId, long deadlineMs);
}
