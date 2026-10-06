package com.game.battle.port;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.SceneEventKind;
import com.game.battle.metrics.BattleMetrics.SceneEventResult;
import com.game.proto.BattleRouting;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SceneBattleEvents} 的 6.2 缺省实现：只打 DEBUG 日志、计 {@code xm_battle_scene_events_total{kind=confirm, result=logged}}。
 * 补发节奏（首发 + 17 次）由房间驱动，与 6.3 起生产装配的真实传输（{@code DubboSceneBattleEvents}）相同；这个实现留给不接传输的测试装配。
 * 不阻塞、不抛异常，线程安全。
 */
public final class LoggingSceneBattleEvents implements SceneBattleEvents {

    private static final Logger log = LoggerFactory.getLogger(LoggingSceneBattleEvents.class);

    private final BattleMetrics metrics;

    public LoggingSceneBattleEvents(BattleMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void confirm(BattleRouting routing, long playerId, long battleId, long deadlineMs) {
        metrics.sceneEvent(SceneEventKind.CONFIRM, SceneEventResult.LOGGED);
        if (log.isDebugEnabled()) {
            log.debug("确认事件（6.2 只记日志，传输由 6.3 接入） battle_id={} player_id={} deadline_ms={} scene_node={} scene_instance={} zone={}",
                    Long.toUnsignedString(battleId), Long.toUnsignedString(playerId), Long.toUnsignedString(deadlineMs),
                    routing.getSceneNodeId(), routing.getSceneInstanceId(), routing.getZoneId());
        }
    }
}
