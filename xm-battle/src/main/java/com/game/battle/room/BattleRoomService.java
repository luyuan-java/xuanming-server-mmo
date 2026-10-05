package com.game.battle.room;

import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.eBattleTicketRole;
import java.util.OptionalInt;

/**
 * 房间服务：全部房间的唯一入口（基线 {@code BattleRoomManager}，{@code room.cpp:486-1653}、{@code :2033-2081}；battle-node-spec §4、§5、§7.6）。
 * 实现 {@link BattleRoomServiceImpl}，依赖见 {@link RoomDependencies}。
 *
 * <p><b>线程</b>：除 {@link #roomCount()} 外，全部方法<b>只在逻辑线程上调用</b>，实现在方法开头 {@link BattleScheduler#assertInLoop()}；
 * 内部无锁。调用方：
 * <ul>
 *   <li>控制面（{@code com.game.battle.rpc} / {@code admin} / {@code BattleNode}）：Dubbo / Tomcat 线程经 {@link BattleScheduler#execute}
 *       投递进逻辑线程再调；createBattle 之前由调用方在同一个逻辑任务里复核准入闸（{@code closed_in_loop}）。</li>
 *   <li>直连面（{@code com.game.battle.edge}）：直连 I/O 就在逻辑线程上，直接调。</li>
 * </ul>
 *
 * <p><b>写出顺序</b>：处理器内部的推送经 {@link DirectLink#send} 立刻写出；直连面在处理器返回之后才写应答（R2）。收尾时房间先摘槽、
 * 再 {@link DirectLink#closeGracefully}（R3）。处理器可能当场结算并删房（R8）：实现必须在结算之前填完应答、之后不再碰房间。
 *
 * <p><b>身份</b>：直连上行的 {@code playerId} 一律来自已验证的票据（直连面传入），请求体里没有、也不信任 player_id；房间按请求体里的
 * {@code battle_id} 查。业务错误一律放在应答的 {@code error_message} 里（成功时<b>不填</b>，应答体 0 字节），方法本身不因业务原因抛异常。
 *
 * <p><b>关直连的原因</b>（{@code BattleMetrics.Disconnect}，计入 {@code xm_battle_disconnects_total}）：重连顶替 {@code REPLACED}
 * （{@link DirectLink#closeNow}）；{@link #abortAll} 的 {@code SHUTDOWN}；其余（收尾、整场期限、销毁、退出观战、观众被清退、幂等重签失败摘除）
 * 一律 {@code BATTLE_CLOSED}（{@link DirectLink#closeGracefully}）。
 *
 * <p><b>指标</b>：房间实现负责计 {@code room_creates}（除 {@code NOT_ALLOCATABLE}，那是控制面计的）、{@code room_ends}、{@code fingerprint_mismatch}、
 * {@code rounds}、{@code round_resolve}、{@code pushes}、{@code tickets}；{@link #roomCount()} 由装配方绑定到 {@code BattleMetrics#bindRooms}。
 */
public interface BattleRoomService {

    // ---------------------------------------------------------------- 控制面（逻辑线程）

    /**
     * 建房（§4.3.3 判定顺序、§4.3.4 插表后的副作用顺序）。{@code response.battle_id} 每条路径都回填。
     * 幂等命中 → 无 error_message、零副作用；拒绝（1005 / 1006 / 1002 / 1003）→ 零副作用（不插表、不触发 hooks、不装计时器、不推送、不发确认）。
     *
     * @param origin {@link RoomOrigin#DEV} 的房间永不投递结算与结果事件
     */
    CreateBattleResponse createBattle(CreateBattleRequest request, RoomOrigin origin);

    /** 作废一间房（§4.7）：观众收 166 {ABORTED, ONGOING} 后关直连；参战者不收任何帧、只看到 FIN；不结算、不发结果事件。房间不在时幂等。 */
    void destroyBattle(DestroyBattleRequest request);

    /**
     * 停机作废全部房间（§4.7 AbortAllRooms）：先拷 id 再逐间处理，每间触发一次 onRemoved。调用方必须在<b>同一个</b>逻辑任务里先关准入闸。
     *
     * @param reason 日志用（如 {@code node_shutdown}）
     */
    void abortAll(String reason);

    /** 补签票据（§2.6）：player 为 0 / 房间不在 / 非成员 → 1005；参战者优先；签不出 → 1003 且不带 assignment。 */
    IssueBattleTicketResponse issueBattleTicket(IssueBattleTicketRequest request);

    /** 登记观众（§5.5，Q1 放进 6.2）：判定顺序 1004 → 1005 → 1005 → 幂等重签 / 重推 → 1008（满 20）→ 新观众签票、登记、推 177。 */
    AddObserverResponse addObserver(AddObserverRequest request);

    /** 清退观众（§5.5）：推 166 {REMOVED, ONGOING}、摘除、关直连；房间或观众不在时什么也不做。 */
    void removeObserver(RemoveObserverRequest request);

    // ---------------------------------------------------------------- 直连面（逻辑线程）

    /**
     * 握手第 5 步：挂接直连（§3.4、R7）。房间存在、且该玩家<b>按票上的角色</b>在对应名单上（参战票只认参战名单、观众票只认观众名单）才成功。
     * 同一玩家已有直连时先把旧的移出槽并 {@link DirectLink#closeNow}（不给旧连接发任何帧），再放入新的。
     * <b>不得</b>经 {@code link} 写任何帧（握手应答必须是直连上的第一帧，R1）。
     *
     * @return 成功时返回该玩家在快照路由里的大厅会话号（只进日志）；失败时 empty（直连面回 {@code battle not found or player not in this battle}）
     */
    OptionalInt attachDirect(long battleId, long playerId, eBattleTicketRole role, DirectLink link);

    /**
     * 握手成功、应答已写出之后调用（R1、O2）：只对观众推 161 {@code SpectateStateS2C} 首帧（观众数 = 当前观众数），参战者什么也不推。
     */
    void onDirectVerified(long battleId, long playerId, eBattleTicketRole role);

    /**
     * 已验证的直连断开（直连面的 {@code channelInactive}）：只在槽里仍是<b>同一个</b> {@code link}（引用相等）时摘除；
     * 房间已不在或槽里已是别的连接时什么也不做（R7）。
     */
    void detachDirect(long battleId, long playerId, DirectLink link);

    /**
     * 149 提交行动（§5.2）：1012 → 1005（房间不在）→ 1005（非参战者，观众也算）→ 引擎 tip 原样（0 兜底成 1005）→ 收下；
     * 全员就绪当场结算一回合（含全自动房间里的合法提交，B2）。成功时 error_message 不填（应答体 0 字节）。
     */
    SubmitBattleActionResponse submit(long playerId, SubmitBattleActionRequest request);

    /**
     * 140 补拉状态（§5.3）：房间不在 / player 为 0 / 既不是参战者也不是观众 → 默认值（battle_id = 0，0 字节，不回错误码）；
     * 否则返回<b>已按视角裁剪</b>的快照（参战者：只留本人与本人宝宝的冷却 + self_items；观众：全清冷却、无 self_items）。
     */
    BattleStateS2C getState(long playerId, GetBattleStateRequest request);

    /**
     * 162 自动战斗开关（§5.4）：1012 → 1005 → 1005（观众不能切）→ 引擎 tip（成功码 1000）→ 只有 {@code enabled && !wasAllReady && allReady}
     * 时当场结算。成功时 error_message 不填。
     */
    SetAutoBattleResponse setAuto(long playerId, SetAutoBattleRequest request);

    /**
     * 165 退出观战（§5.5）：房间不在、不是观众都回成功；是观众则移出名单并 {@link DirectLink#closeGracefully}（应答写出之后才 FIN，O8）。
     * 不推 166。
     */
    StopWatchBattleResponse stopWatch(long playerId, StopWatchBattleRequest request);

    // ---------------------------------------------------------------- 任意线程

    /** 当前房间数（房间表 hooks 维护的原子量；目录发布与指标从别的线程读）。 */
    int roomCount();
}
