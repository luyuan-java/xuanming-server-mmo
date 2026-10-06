package com.game.battle.port;

import com.game.proto.contracts.kafka.BattleResultEvent;

/**
 * 带活动上下文的对局结果出站端口（基线 {@code battle_result_activity.h}：先 {@code SET battle:activity_result:{id}} 再发 Kafka，未销账就重发；
 * battle-node-spec §4.9、§7.9；scene-battle-spec §7.17）。生产实现是 6.3 的活动结果通道（{@code ActivityResultOutbox}：先落
 * {@code xm:battle:activity-result:{battle_id}}、再经 {@link BattleResultSink} 按 {@code channel=activity} 发布、未销账就重发）；
 * {@link LoggingActivityResultSink} 只记日志，留给不接传输的测试装配。
 *
 * <p>契约：只在逻辑线程上调用；实现必须<b>异步、不阻塞、不抛异常</b>，持久与重发由实现负责。调用点：真正打完的局（正常收尾、整场期限）
 * 且 {@code activity_context.kind ≠ NONE}（<b>不认识的 kind 值也按活动局处理</b>）时调本端口，否则调 {@link BattleResultSink}；
 * Destroy / 停机作废不调；{@code RoomOrigin.DEV} 的房间不调。消费方必须按 battle_id 幂等（可能投递多次）。
 */
public interface ActivityResultSink {

    void dispatch(BattleResultEvent event);
}
