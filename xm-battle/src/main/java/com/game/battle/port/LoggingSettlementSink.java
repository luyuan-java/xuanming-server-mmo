package com.game.battle.port;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.SceneEventKind;
import com.game.battle.metrics.BattleMetrics.SceneEventResult;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SettlementSink} 的 6.2 缺省实现：打 INFO、计 {@code xm_battle_scene_events_total{kind=settlement, result=logged}}。
 * 不阻塞、不抛异常，线程安全。
 */
public final class LoggingSettlementSink implements SettlementSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingSettlementSink.class);

    private final BattleMetrics metrics;

    public LoggingSettlementSink(BattleMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void dispatch(BattleRouting routing, long playerId, BattleSettlementData settlement) {
        metrics.sceneEvent(SceneEventKind.SETTLEMENT, SceneEventResult.LOGGED);
        log.info("结算（6.2 只记日志，传输由 6.3 接入） battle_id={} player_id={} outcome={} team={} rounds={} exp={} gold={} dead={} fled={} "
                        + "consumed={} gained={} scene_node={} zone={}",
                Long.toUnsignedString(settlement.getBattleId()), Long.toUnsignedString(playerId), settlement.getOutcome(),
                settlement.getPlayerTeamIndex(), settlement.getTotalRounds(), Long.toUnsignedString(settlement.getExpGain()),
                Long.toUnsignedString(settlement.getGoldGain()), settlement.getIsDead(), settlement.getFled(),
                settlement.getItemsConsumedCount(), settlement.getItemsGainedCount(), routing.getSceneNodeId(), routing.getZoneId());
    }
}
