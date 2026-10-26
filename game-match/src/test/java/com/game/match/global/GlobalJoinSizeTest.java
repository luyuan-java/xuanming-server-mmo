package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.MatchProperties;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.support.MatchTip;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.MatchMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 全球 SQL 入口人数档必须在战斗/准入写之前验证；老客户端 0 与各合法档使用同一规则。 */
class GlobalJoinSizeTest {
    private final GlobalMatchStore store = mock(GlobalMatchStore.class);
    private final PlayerStatusReader players = mock(PlayerStatusReader.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchProperties props = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
    private final SessionContext session = SessionContext.newBuilder().setPlayerId(7).setAccount("原账号")
            .setOwnerRevision(1).setSessionId(9).setGateInstanceId("gate-one").build();

    private GlobalMatchService service() {
        when(players.presence(eq(7L), any())).thenReturn(Optional.of(PlayerPresence.newBuilder().setPlayerId(7)
                .setSessionId(9).setGateInstanceId("gate-one").build()));
        return new GlobalMatchService(store, GlobalRegionPolicyTest.CONFIG, props, null, null, null, null, null, players, null, null)
                .withMetrics(new MatchMetrics(meters, new MetricLabels(id -> id == 1)));
    }

    private JoinQueueRequest request(int config, int size) {
        return JoinQueueRequest.newBuilder().setMode(MatchMode.MATCH_MODE_PVE_TEAM).setBattleConfigId(config)
                .setTeamSize(size).setRequestId(UUID.randomUUID().toString()).build();
    }

    @Test
    void 超限及uint32高半区回专用提示_不查战斗不写准入() {
        try (var service = service()) {
            for (int size : new int[] {6, Integer.MIN_VALUE, -1}) {
                var reply = service.join(session, request(1, size), Deadline.after(1000));
                assertThat(reply.getErrorCode()).isEqualTo(16002);
                assertThat(reply.getErrorMessage()).isEqualTo(MatchTip.JOIN_TEAM_SIZE_OVER_LIMIT.proto());
                assertThat(reply.getQueueTicket()).isEmpty();
            }
            verify(players, never()).inBattle(anyLong(), any());
            verify(store, never()).prepareJoin(any(), any(), anyInt(), anyInt(), anyLong(), anyString());
            assertThat(meters.get("game.match.pve.team.size.requests").tags("size", "invalid", "outcome", "team_size_over_limit")
                    .counter().count()).isEqualTo(3);
        }
    }

    @Test
    void 未开放副本优先回16003_默认与1到5人均进入后续守卫() {
        try (var service = service()) {
            assertThat(service.join(session, request(999, -1), Deadline.after(1000)).getErrorMessage())
                    .isEqualTo(MatchTip.JOIN_TEAM_SIZE_NOT_CONFIGURED.proto());
            when(players.inBattle(eq(7L), any())).thenReturn(true);
            for (int size = 0; size <= 5; size++) {
                assertThat(service.join(session, request(1, size), Deadline.after(1000)).getErrorMessage())
                        .isEqualTo(MatchTip.JOIN_IN_BATTLE.proto());
            }
            verify(players, times(6)).inBattle(eq(7L), any());
            assertThat(meters.get("game.match.pve.team.size.requests").tags("size", "5", "outcome", "in_battle")
                    .counter().count()).isEqualTo(2);
        }
    }
}
