package com.game.battle.port;

import com.game.proto.contracts.kafka.BattleResultEvent;

/**
 * 普通局的对局结果出站端口（基线 Kafka topic {@code match-results}，key battle_id，payload 就是 {@code BattleResultEvent}，
 * {@code room.cpp:283-304}；battle-node-spec §4.9、§7.9）。6.2 的缺省实现 {@link LoggingBattleResultSink} 只记日志；
 * 真实传输由 6.4 接入（推荐见 Q12）。
 *
 * <p>契约：只在逻辑线程上调用；实现必须<b>异步、不阻塞、不抛异常</b>。调用点：真正打完的局（正常收尾、整场期限 DRAW）且没有活动上下文
 * （{@code kind == NONE}）时调用一次；Destroy / 停机作废不调；{@code RoomOrigin.DEV} 的房间不调。
 */
public interface BattleResultSink {

    void publish(BattleResultEvent event);
}
