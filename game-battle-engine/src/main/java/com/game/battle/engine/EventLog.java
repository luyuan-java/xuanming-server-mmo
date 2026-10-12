package com.game.battle.engine;

import com.game.proto.BattleEventItem;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleEventType;

/**
 * 回合事件的写入口与表现层分组游标（基线 {@code AppendEvent} / {@code BeginEventGroup}，{@code engine.cpp:1772-1788}；规格 §3.4）。
 *
 * <ul>
 *   <li>{@link #append} 只写 event_type / source_id / target_id，并盖上当前的 group_id / hit_index；其余字段由调用方在返回的
 *       builder 上补（同基线返回事件指针后再 set）。</li>
 *   <li>{@link #beginGroup()}：{@code ++group; hit = 0}。每回合开始时 {@link #startRound} 归零，所以组号每回合从 1 起。</li>
 *   <li>hit_index 只在技能逐目标落地时被设成目标序，结束后归 0（{@code engine.cpp:847-855}）。</li>
 * </ul>
 * 事件写进当前的「接收者」（本回合的 {@link TurnResultS2C.Builder}）。不在结算中时接收者是一个丢弃用的 builder，
 * 测试钩子 {@code addBuffForTest} 也借此丢弃事件（同基线友元用局部 {@code TurnResultS2C}，{@code test.cpp:26-38}）。
 */
final class EventLog {

    private TurnResultS2C.Builder sink = TurnResultS2C.newBuilder();
    /** uint32；基线 {@code currentGroupId}。 */
    private int groupId;
    /** uint32；基线 {@code currentHitIndex}。 */
    private int hitIndex;

    /** 回合结算开始：事件写进 {@code roundResult}，组号与段序归 0（{@code engine.cpp:627-628}）。 */
    void startRound(TurnResultS2C.Builder roundResult) {
        sink = roundResult;
        groupId = 0;
        hitIndex = 0;
    }

    /** 回合结算结束：不再持有已交出的结果，之后的事件（只可能来自测试钩子）进丢弃用的 builder。 */
    void endRound() {
        sink = TurnResultS2C.newBuilder();
    }

    /** 换接收者并返回原接收者；游标不动（基线测试友元同样沿用当前游标）。 */
    TurnResultS2C.Builder redirect(TurnResultS2C.Builder newSink) {
        TurnResultS2C.Builder previous = sink;
        sink = newSink;
        return previous;
    }

    /** {@code BeginEventGroup}：开新的一组。 */
    void beginGroup() {
        groupId++;
        hitIndex = 0;
    }

    /** 技能逐目标落地时设目标序；落地结束后调用方再设回 0。 */
    void setHitIndex(int index) {
        hitIndex = index;
    }

    /** {@code AppendEvent}：追加一条事件并盖上当前组号 / 段序，返回挂在结果上的 builder 供调用方补字段。 */
    BattleEventItem.Builder append(eBattleEventType eventType, long sourceId, long targetId) {
        return sink.addEventsBuilder()
                .setEventType(eventType)
                .setSourceId(sourceId)
                .setTargetId(targetId)
                .setGroupId(groupId)
                .setHitIndex(hitIndex);
    }
}
