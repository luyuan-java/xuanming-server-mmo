package com.game.battle.port;

import com.game.proto.contracts.kafka.BattleResultEvent;

/**
 * 对局结果事件的发布端口（基线 Kafka topic {@code match-results}，key battle_id，payload 就是 {@code BattleResultEvent}，
 * {@code room.cpp:283-304}；battle-node-spec §4.9、§7.9；scene-battle-spec §7.17）。生产装配自 6.4 起是 Kafka 生产方
 * {@code port.kafka.KafkaBattleResultSink}（topic {@code xm-battle-result-g<代次>}，match-spec §5.4，落实 Q12）；
 * {@link LoggingBattleResultSink} 只记日志，留给不接 Kafka 的测试装配。
 *
 * <p>自 6.3 起它有<b>两个调用方、两条线程</b>，实现必须线程安全，并且<b>异步、不阻塞、不抛异常</b>：
 * <ul>
 *   <li><b>普通局</b>（{@link Channel#PLAIN}）：房间在 {@code battle-logic} 上调 {@link #publish(BattleResultEvent)}。真正打完的局（正常收尾、
 *       整场期限 DRAW）且没有活动上下文（{@code kind == NONE}）时<b>调用一次</b>；Destroy / 停机作废不调；{@code RoomOrigin.DEV} 与
 *       {@code DEV_GATHER} 的房间不调。</li>
 *   <li><b>活动局</b>（{@link Channel#ACTIVITY}）：活动结果通道（{@code ActivityResultOutbox}）在 {@code battle-outbox} 上调
 *       {@link #publish(BattleResultEvent, Channel)}。持久副本落库之后首发一次，之后未销账就每 10 s <b>重发同一个事件对象</b>（不可变，序列化结果与落库的
 *       字节相同），一局最多 1 + {@code ACTIVITY_RETRY_MAX}（30）= 31 次；落库失败时只发一次。带活动上下文（不认识的 kind 也算）。</li>
 * </ul>
 * 所以实现<b>必须容忍同一 battle_id 被多次调用</b>（消费方按 battle_id 幂等），并且按 {@code channel} 分开计数
 * （{@code xm_battle_results_total{channel}}：{@code plain} 只统计普通局，活动局的首发与重发都计 {@code activity}）。
 */
@FunctionalInterface
public interface BattleResultSink {

    /** 结果事件走的通道（指标 {@code xm_battle_results_total{channel}} 的取值）。 */
    enum Channel {
        /** 普通局：房间直接发，一局一次。 */
        PLAIN,
        /** 活动局：经活动结果通道发，首发 + 重发。 */
        ACTIVITY
    }

    /**
     * 发布一次结果事件。
     *
     * @param event   结果事件（不可变；不得修改）
     * @param channel 调用方所在的通道（决定计数；传输与载荷相同）
     */
    void publish(BattleResultEvent event, Channel channel);

    /** 普通局的发布（房间在 {@code battle-logic} 上调，一局一次）。 */
    default void publish(BattleResultEvent event) {
        publish(event, Channel.PLAIN);
    }
}
