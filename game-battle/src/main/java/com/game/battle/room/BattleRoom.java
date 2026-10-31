package com.game.battle.room;

import com.game.battle.engine.TurnBattleEngine;
import com.game.battle.metrics.BattleMetrics;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleRouting;
import com.game.proto.BattleStateS2C;
import com.game.proto.TurnResultS2C;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eSpectateEndReason;
import com.game.proto.eSpectateVisibility;
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
    /** 原观战操作号；新操作不得覆盖仍在房内的旧操作。 */
    final Map<Long,String> observerOperations=new HashMap<>();
    /** observer_id → 观众席宽限计时器（登记后待建直连 / 断线待重连）；建起直连时撤销，摘除时只经 {@code cancelObserverOperation} 一处撤销。 */
    final Map<Long, Cancellable> observerGrace = new HashMap<>();
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
    boolean requiresResultConfirmation;
    /**
     * 观战中继开关（只升不降；mmorpg battle-spectate-tiered-fanout.md §15.4、§15.7）：打开后每帧观众帧发布一次到这一场的 Redis 分片频道，
     * 并写最新帧键。Java 版中继档与广播档是同一个开关。
     */
    boolean spectateRelayEnabled;
    /** 最近发布的观战帧的回合（终局帧的 {@code round_index} 取它）。 */
    int lastSpectateRound;
    /**
     * 这一场最后一帧观战帧的发布结局（只在逻辑线程上读写，随房间回收）：下一帧接在它后面发。Redisson 紧挨着发出的命令走不同的连接、没有先后可言，
     * 不等上一帧的话 END 会越过最后一回合的 TURN，166 不带状态，观众就再也看不到那一回合。
     */
    java.util.concurrent.CompletionStage<Void> spectateTail = java.util.concurrent.CompletableFuture.completedFuture(null);
    /** 原建房完整正文，包含不可替换的operation/instance和实际采用的终局协议。 */
    com.game.proto.CreateBattleRequest originalCreate=com.game.proto.CreateBattleRequest.getDefaultInstance();
    PendingCompletion pendingCompletion;

    /** 原结果不可改；仅逻辑线程更新尝试状态，未知不构成终局。 */
    static final class PendingCompletion {
        final BattleResultEvent event;
        final com.google.protobuf.ByteString original;
        final TurnResultS2C finalTurn;
        final BattleStateS2C visibleBefore;
        final eSpectateEndReason spectateReason;
        final BattleMetrics.RoomEnd endReason;
        int failures;
        boolean inFlight;
        Cancellable retryTimer = Cancellable.NONE;
        PendingCompletion(BattleResultEvent event,TurnResultS2C finalTurn,BattleStateS2C visibleBefore,
                          eSpectateEndReason spectateReason,BattleMetrics.RoomEnd endReason) {
            this.event=event;this.original=event.toByteString();this.finalTurn=finalTurn;this.visibleBefore=visibleBefore;
            this.spectateReason=spectateReason;this.endReason=endReason;
        }
    }

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

    /**
     * 这局能不能被观战：只读建房原件里冻结的 {@code spectate_visibility}（match 建房时按单一策略表决定一次，battle 不按模式重算）。
     * 只认 PUBLIC：漏填（老发送方）与私密一律不可观战，AddObserver 的第二道闸据此拒绝。
     */
    boolean spectatePublic() {
        return originalCreate.getSpectateVisibility() == eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC;
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
        if (pendingCompletion != null) pendingCompletion.retryTimer.cancel();
    }
}
