package com.game.battle.port;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.ResultChannel;
import com.game.battle.metrics.BattleMetrics.ResultOutcome;
import com.game.proto.contracts.kafka.BattleResultEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BattleResultSink} 的 6.2 缺省实现：打 INFO、计 {@code xm_battle_results_total{channel=plain, result=logged}}。
 * 不阻塞、不抛异常，线程安全。
 */
public final class LoggingBattleResultSink implements BattleResultSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingBattleResultSink.class);

    private final BattleMetrics metrics;

    public LoggingBattleResultSink(BattleMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void publish(BattleResultEvent event) {
        metrics.result(ResultChannel.PLAIN, ResultOutcome.LOGGED);
        log.info("对局结果（6.2 只记日志，传输由 6.4 接入） battle_id={} match_mode={} config={} outcome={} winner_team={} teams={} rounds={} "
                        + "fled={} dead={} finished_at_ms={}",
                Long.toUnsignedString(event.getBattleId()), event.getMatchMode(), event.getBattleConfigId(), event.getOutcome(),
                event.getWinnerTeamIndex(), event.getTeamsCount(), event.getTotalRounds(), event.getFledPlayerIdsCount(),
                event.getDeadPlayerIdsCount(), Long.toUnsignedString(event.getFinishedAtMs()));
    }
}
