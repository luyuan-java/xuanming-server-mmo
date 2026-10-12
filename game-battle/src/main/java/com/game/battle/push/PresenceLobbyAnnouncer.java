package com.game.battle.push;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.LobbyOutcome;
import com.game.discovery.presence.PlayerPushes;
import com.game.proto.MessageContent;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LobbyAnnouncer} 的生产实现：{@link PlayerPushes#pushAllToPlayer}——查一次在线目录取玩家当前会话，发布一条
 * {@code GatePush{message_batch}}，gate 在会话 EventLoop 的同一个任务里逐条过玩家栅栏后按序下发（battle-node-spec §7.7）。
 *
 * <p>线程：{@link #announce} 在逻辑线程上调用，只发起异步操作；结局在 Redisson 回调线程上<b>只计数、打日志</b>，不碰房间。
 * 调用本身抛出的异常（例如 Redisson 已关闭）也被吞掉并计 error：公告是至多一次的，不得把房间的收尾流程打断。
 */
public final class PresenceLobbyAnnouncer implements LobbyAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(PresenceLobbyAnnouncer.class);

    private final PlayerPushes pushes;
    private final BattleMetrics metrics;

    public PresenceLobbyAnnouncer(PlayerPushes pushes, BattleMetrics metrics) {
        this.pushes = Objects.requireNonNull(pushes, "pushes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void announce(long playerId, List<MessageContent> contents) {
        if (contents.isEmpty()) {
            return;
        }
        try {
            pushes.pushAllToPlayer(playerId, contents).whenComplete((outcome, error) -> {
                if (error != null) {
                    metrics.lobbyPushOutcome(LobbyOutcome.ERROR);
                    log.warn("大厅公告经 gate 推送失败（至多一次，丢弃） player_id={} 条数={}", Long.toUnsignedString(playerId),
                            contents.size(), error);
                    return;
                }
                metrics.lobbyPushOutcome(LobbyOutcome.of(outcome));
                if (outcome != PlayerPushes.Outcome.SENT) {
                    log.info("大厅公告没有送到 gate（{}） player_id={} 条数={}", outcome, Long.toUnsignedString(playerId), contents.size());
                }
            });
        } catch (RuntimeException e) {
            metrics.lobbyPushOutcome(LobbyOutcome.ERROR);
            log.warn("大厅公告发起失败（至多一次，丢弃） player_id={} 条数={}", Long.toUnsignedString(playerId), contents.size(), e);
        }
    }
}
