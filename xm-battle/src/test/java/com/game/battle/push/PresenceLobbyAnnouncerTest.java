package com.game.battle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.protocol.BattleFrames;
import com.game.discovery.presence.PlayerPushes;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleStartS2C;
import com.game.proto.MessageContent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 大厅公告经 gate 回落：一组公告一次交给 {@code pushAllToPlayer}（177 → 143 保序，battle-node-spec §7.7），结局只计数。 */
class PresenceLobbyAnnouncerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final PlayerPushes pushes = mock(PlayerPushes.class);
    private final PresenceLobbyAnnouncer announcer = new PresenceLobbyAnnouncer(pushes, metrics);

    private static final MessageContent ASSIGNED = BattleFrames.push(177, BattleAssignedS2C.newBuilder().setBattleId(5).build());
    private static final MessageContent START = BattleFrames.push(143, BattleStartS2C.newBuilder().setBattleId(5).build());

    private double outcome(String name) {
        return registry.get("xm.battle.lobby.push.outcomes").tag("outcome", name).counter().count();
    }

    @Test
    void 两条公告一次交出_顺序不变_送达计sent() {
        when(pushes.pushAllToPlayer(9001L, List.of(ASSIGNED, START)))
                .thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.SENT));

        announcer.announce(9001L, List.of(ASSIGNED, START));

        verify(pushes).pushAllToPlayer(9001L, List.of(ASSIGNED, START));
        assertThat(outcome("sent")).isEqualTo(1);
    }

    @Test
    void 不在线与gate不可达只计数() {
        when(pushes.pushAllToPlayer(anyLong(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.OFFLINE))
                .thenReturn(CompletableFuture.completedFuture(PlayerPushes.Outcome.GATE_UNREACHABLE));

        announcer.announce(1L, List.of(ASSIGNED));
        announcer.announce(2L, List.of(ASSIGNED));

        assertThat(outcome("offline")).isEqualTo(1);
        assertThat(outcome("gate_unreachable")).isEqualTo(1);
    }

    @Test
    void Redis故障与发起时抛异常都只计error_不往外抛() {
        when(pushes.pushAllToPlayer(anyLong(), anyList()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("测试：Redis 故障")))
                .thenThrow(new IllegalStateException("测试：Redisson 已关闭"));

        announcer.announce(1L, List.of(ASSIGNED));
        announcer.announce(2L, List.of(ASSIGNED));

        assertThat(outcome("error")).isEqualTo(2);
    }

    @Test
    void 空列表什么也不做() {
        announcer.announce(1L, List.of());
        verify(pushes, never()).pushAllToPlayer(anyLong(), anyList());
    }
}
