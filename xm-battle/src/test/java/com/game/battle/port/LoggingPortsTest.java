package com.game.battle.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.BattleResultSink.Channel;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 出站端口的日志缺省实现：只记日志、计 logged（battle-node-spec §7.9）；结果发布端口按通道分开计（scene-battle-spec §7.17，审计 OBX-12）。 */
class LoggingPortsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final BattleRouting routing = BattleRouting.newBuilder().setSceneNodeId(3).setSceneInstanceId("scene-uuid").setZoneId(1)
            .setGateNodeId(2).setGateInstanceId("gate-uuid").setSessionId(77).build();

    private double sceneEvents(String kind) {
        return registry.get("xm.battle.scene.events").tag("kind", kind).tag("result", "logged").counter().count();
    }

    private double results(String channel) {
        return registry.get("xm.battle.results").tag("channel", channel).tag("result", "logged").counter().count();
    }

    @Test
    void 确认与结算只计logged() {
        new LoggingSceneBattleEvents(metrics).confirm(routing, 9001L, -5L, 1_700_000_192_000L);
        new LoggingSettlementSink(metrics).dispatch(routing, 9001L,
                BattleSettlementData.newBuilder().setBattleId(-5L).setPlayerId(9001L).build());

        assertThat(sceneEvents("confirm")).isEqualTo(1);
        assertThat(sceneEvents("settlement")).isEqualTo(1);
    }

    @Test
    void 结果事件按通道计logged() {
        BattleResultEvent event = BattleResultEvent.newBuilder().setBattleId(-5L).setTotalRounds(3).build();
        new LoggingBattleResultSink(metrics).publish(event);
        new LoggingActivityResultSink(metrics).dispatch(event);

        assertThat(results("plain")).isEqualTo(1);
        assertThat(results("activity")).isEqualTo(1);
    }

    @Test
    void 结果发布端口_不带通道的入口就是普通局_计plain() {
        BattleResultSink sink = new LoggingBattleResultSink(metrics);
        BattleResultEvent event = BattleResultEvent.newBuilder().setBattleId(7).build();

        sink.publish(event);
        sink.publish(event, Channel.PLAIN);

        assertThat(results("plain")).isEqualTo(2);
        assertThat(results("activity")).isZero();
    }

    @Test
    void 结果发布端口_活动通道的首发与重发都计activity_不混进plain() {
        BattleResultSink sink = new LoggingBattleResultSink(metrics);
        BattleResultEvent event = BattleResultEvent.newBuilder().setBattleId(7).build();

        sink.publish(event, Channel.ACTIVITY);
        sink.publish(event, Channel.ACTIVITY);
        sink.publish(event, Channel.ACTIVITY);

        assertThat(results("activity")).isEqualTo(3);
        assertThat(results("plain")).isZero();
    }
}
