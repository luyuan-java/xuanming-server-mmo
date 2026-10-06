package com.game.battle.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics.CreateResult;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.metrics.BattleMetrics.HandshakeResult;
import com.game.battle.metrics.BattleMetrics.RequestResult;
import com.game.battle.metrics.BattleMetrics.RpcMethod;
import com.game.battle.metrics.BattleMetrics.RpcResult;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.protocol.BattleMessageIds.Upstream;
import com.game.battle.push.PushCategory;
import com.game.battle.push.PushRoute;
import com.game.battle.room.FingerprintMode;
import com.game.common.token.BattleTickets;
import com.game.discovery.presence.PlayerPushes;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 指标名、标签与预建（battle-node-spec §9）：按 Prometheus 导出格式核对。 */
class BattleMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final BattleMetrics metrics = new BattleMetrics(registry);

    @Test
    void 全部指标在构造时注册_名字同规格() {
        String scrape = registry.scrape();
        assertThat(scrape).contains(
                "xm_battle_rooms ",
                "xm_battle_room_creates_total{result=\"not_allocatable\"}",
                "xm_battle_room_ends_total{reason=\"deadline\"}",
                "xm_battle_fingerprint_mismatch_total{mode=\"enforce\"}",
                "xm_battle_rounds_total{trigger=\"auto_flip\"}",
                "xm_battle_round_resolve_seconds_bucket",
                "xm_battle_direct_connections ",
                "xm_battle_handshakes_total{result=\"ticket_not_in_roster\"}",
                "xm_battle_client_requests_total{method=\"SubmitBattleAction\",result=\"rate_limited\"}",
                "xm_battle_client_requests_total{method=\"other\",result=\"not_allowed\"}",
                "xm_battle_invalid_frames_total{reason=\"checksum\"}",
                "xm_battle_disconnects_total{reason=\"write_buffer_full\"}",
                "xm_battle_pushes_total{category=\"lobby\",message=\"NotifyBattleAssigned\",route=\"via_gate\"}",
                "xm_battle_pushes_total{category=\"battle_frame\",message=\"NotifyTurnResult\",route=\"dropped\"}",
                "xm_battle_lobby_push_outcomes_total{outcome=\"gate_unreachable\"}",
                "xm_battle_tickets_total{path=\"reissue\",result=\"failed\"}",
                "xm_battle_scene_events_total{kind=\"confirm\",result=\"logged\"}",
                "xm_battle_results_total{channel=\"activity\",result=\"logged\"}",
                "xm_battle_rpc_seconds_bucket",
                "xm_battle_logic_pending_tasks ",
                "xm_battle_admission_phase ",
                "xm_battle_lease_lost_total ");
        assertThat(scrape).doesNotContain("fingerprint_mismatch_total{mode=\"off\"}");
    }

    @Test
    void dev_gather指标预建且按模式与结局计数() {
        assertThat(registry.scrape()).contains("xm_battle_dev_gather_total{mode=\"prepare_only\",result=\"ok\"} 0.0",
                "xm_battle_dev_gather_total{mode=\"create\",result=\"create_failed\"} 0.0",
                "xm_battle_dev_gather_total{mode=\"unknown\",result=\"forbidden\"} 0.0");

        metrics.devGather(BattleMetrics.DevGatherMode.CREATE, BattleMetrics.DevGatherResult.PREPARE_FAILED);

        assertThat(registry.scrape()).contains("xm_battle_dev_gather_total{mode=\"create\",result=\"prepare_failed\"} 1.0");
    }

    @Test
    void 计数与Gauge绑定() {
        metrics.roomCreate(CreateResult.OK);
        metrics.handshake(HandshakeResult.of(BattleTickets.Verdict.EXPIRED));
        metrics.clientRequest(Upstream.GET_BATTLE_STATE, RequestResult.OK);
        metrics.clientRequest(null, RequestResult.NOT_ALLOWED);
        metrics.disconnect(Disconnect.REPLACED);
        metrics.push(PushCategory.BATTLE_FRAME, PushRoute.DIRECT, Notify.SPECTATE_END);
        metrics.push(PushCategory.LOBBY_ANNOUNCEMENT, PushRoute.DROP, Notify.BATTLE_START);
        metrics.fingerprintMismatch(FingerprintMode.OFF);
        metrics.lobbyPushOutcome(BattleMetrics.LobbyOutcome.of(PlayerPushes.Outcome.OFFLINE));
        metrics.rpc(RpcMethod.CREATE_BATTLE, RpcResult.NOT_ALLOCATABLE, 2_000_000);

        AtomicInteger rooms = new AtomicInteger(3);
        AdmissionGate gate = new AdmissionGate();
        gate.open();
        metrics.bindRooms(rooms::get);
        metrics.bindAdmissionPhase(gate::phase);

        String scrape = registry.scrape();
        assertThat(scrape).contains(
                "xm_battle_room_creates_total{result=\"ok\"} 1.0",
                "xm_battle_handshakes_total{result=\"expired\"} 1.0",
                "xm_battle_client_requests_total{method=\"GetBattleState\",result=\"ok\"} 1.0",
                "xm_battle_client_requests_total{method=\"other\",result=\"not_allowed\"} 1.0",
                "xm_battle_disconnects_total{reason=\"replaced\"} 1.0",
                "xm_battle_pushes_total{category=\"battle_frame\",message=\"NotifySpectateEnd\",route=\"direct\"} 1.0",
                "xm_battle_pushes_total{category=\"lobby\",message=\"NotifyBattleStart\",route=\"dropped\"} 1.0",
                "xm_battle_lobby_push_outcomes_total{outcome=\"offline\"} 1.0",
                "xm_battle_rpc_seconds_count{method=\"createBattle\",result=\"not_allocatable\"} 1",
                "xm_battle_rooms 3.0",
                "xm_battle_admission_phase 1.0");
    }

    @Test
    void 标签里没有高基数维度() {
        String scrape = registry.scrape();
        assertThat(scrape).doesNotContain("player_id", "battle_id", "session", "peer", "node_id");
    }
}
