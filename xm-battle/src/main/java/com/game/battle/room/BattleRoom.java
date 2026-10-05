package com.game.battle.room;

import com.game.battle.engine.TurnBattleEngine;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleRouting;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一间战斗房（基线 {@code BattleRoomManager::BattleRoom}，{@code room.h:184-221}；battle-node-spec §4.1、§7.6）。领域对象，
 * <b>只在逻辑线程上用</b>；只由 {@link BattleRoomServiceImpl} 读写。
 *
 * <p>名单与直连槽都用按 player_id <b>无符号</b>升序的 {@link TreeMap}：广播、结算、关闭按这个顺序遍历（R6）。
 * 直连槽里只放活着的 {@link DirectLink}：关闭时立刻移除（AGENTS.md §3 连接生命周期），不长期钉住死连接。
 */
final class BattleRoom {

    final long battleId;
    /** 开局参数副本，只回显进结果事件。 */
    final int matchMode;
    final int battleConfigId;
    /** {@code CreateBattleRequest.activity_context} 原样副本；结束时回显，并据 kind 选结果通道。 */
    final BattleActivityContext activityContext;
    final RoomOrigin origin;
    /** 一房一个引擎（非线程安全，限定在逻辑线程）。 */
    final TurnBattleEngine engine;
    /** 整场作废期限（Unix ms）：= 整场计时器 = 票据 expire_at_ms = 确认事件 deadline_ms（§10.4 四者同值）。 */
    final long deadlineMs;

    /** player_id → 快照路由（参战名单）。 */
    final NavigableMap<Long, BattleRouting> routingByPlayer = new TreeMap<>(Long::compareUnsigned);
    /** observer_id → 路由（观众名单；scene 字段恒为 0）。 */
    final NavigableMap<Long, BattleRouting> routingByObserver = new TreeMap<>(Long::compareUnsigned);
    /** observer_id → 名字（日志用）。 */
    final Map<Long, String> observerNames = new HashMap<>();
    /** player_id → 已验证直连，参战与观战共用一个槽（单槽互斥）。 */
    final NavigableMap<Long, DirectLink> directByPlayer = new TreeMap<>(Long::compareUnsigned);

    /** 回合截止计时器。 */
    Cancellable roundTimer = Cancellable.NONE;
    /** 整场期限计时器。 */
    Cancellable battleTimer = Cancellable.NONE;
    /** 确认补发计时器。 */
    Cancellable confirmResendTimer = Cancellable.NONE;
    /** 当前回合截止（Unix ms），回填进各类 state 的 {@code action_deadline_ms}。 */
    long actionDeadlineMs;
    /** 确认事件剩余补发次数（§11 N19：计次代替墙钟窗口）。 */
    int confirmResendsLeft = RoomConstants.CONFIRM_RESENDS;
    /** 从表里移除之前置位：迟到的计时器回调据此识别出房间已死（§11 N14，配合对象身份比较）。 */
    boolean closed;

    BattleRoom(long battleId, int matchMode, int battleConfigId, BattleActivityContext activityContext, RoomOrigin origin,
               TurnBattleEngine engine, long deadlineMs) {
        this.battleId = battleId;
        this.matchMode = matchMode;
        this.battleConfigId = battleConfigId;
        this.activityContext = Objects.requireNonNull(activityContext, "activityContext");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.deadlineMs = deadlineMs;
    }

    boolean isParticipant(long playerId) {
        return routingByPlayer.containsKey(playerId);
    }

    boolean isObserver(long playerId) {
        return routingByObserver.containsKey(playerId);
    }

    /** 槽里的直连（没有时 null；是否「活」由出口判定）。 */
    DirectLink directOf(long playerId) {
        return directByPlayer.get(playerId);
    }

    /** 取消三个计时器（收尾、销毁、作废一律先做）。 */
    void cancelTimers() {
        roundTimer.cancel();
        battleTimer.cancel();
        confirmResendTimer.cancel();
    }
}
