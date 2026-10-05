package com.game.battle.port;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.ResultChannel;
import com.game.battle.metrics.BattleMetrics.ResultOutcome;
import com.game.proto.contracts.kafka.BattleResultEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ActivityResultSink} 的 6.2 缺省实现：打 INFO、计 {@code xm_battle_results_total{channel=activity, result=logged}}。
 * 不阻塞、不抛异常，线程安全。
 */
public final class LoggingActivityResultSink implements ActivityResultSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingActivityResultSink.class);

    private final BattleMetrics metrics;

    public LoggingActivityResultSink(BattleMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void dispatch(BattleResultEvent event) {
        metrics.result(ResultChannel.ACTIVITY, ResultOutcome.LOGGED);
        log.info("活动局结果（6.2 只记日志，传输由 6.3 / 4.6 接入） battle_id={} kind={} activity_id={} outcome={} winner_team={} teams={} "
                        + "rounds={} fled={} dead={}",
                Long.toUnsignedString(event.getBattleId()), event.getActivityContext().getKindValue(),
                event.getActivityContext().getActivityId(), event.getOutcome(), event.getWinnerTeamIndex(), event.getTeamsCount(),
                event.getTotalRounds(), event.getFledPlayerIdsCount(), event.getDeadPlayerIdsCount());
    }
}
