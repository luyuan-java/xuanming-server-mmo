package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.port.BattleResultSink;
import com.game.battle.testing.FakeBattleData;
import com.game.proto.BattleAction;
import com.game.proto.BattleReplay;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.CreateBattleRequest;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleReplayEndReason;
import com.game.proto.match.BattleOutcomeDecision;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 战斗房四处录制钩子与 v1 终局提交（含重试冻结字节不变、强制判平与未请求不录）。 */
class BattleReplayRoomTest {

    private final RoomHarness h = new RoomHarness();
    private final List<BattleReplayEnvelope> capturedReplays = new ArrayList<>();
    private final List<CompletableFuture<BattleOutcomeDecision>> decisions = new ArrayList<>();

    BattleReplayRoomTest() {
        h.results = new BattleResultSink() {
            @Override
            public void publish(BattleResultEvent event, Channel channel) {
            }

            @Override
            public CompletableFuture<BattleOutcomeDecision> commitOutcome(
                    CreateBattleRequest creation, BattleResultEvent event, ByteString original,
                    BattleReplayEnvelope replay) {
                capturedReplays.add(replay);
                CompletableFuture<BattleOutcomeDecision> future = new CompletableFuture<>();
                decisions.add(future);
                return future;
            }
        };
    }

    private CreateBattleRequest.Builder pvpV1(long battleId, boolean recordReplay) {
        CreateBattleRequest.Builder builder = h.pvp(battleId, A, B)
                .setPlacementOperationId("902df74e-3843-48e0-bd72-05ed824a4f12")
                .setTargetInstanceId(RoomHarness.INSTANCE)
                .setOutcomeProtocolVersion(1)
                .setRecordReplay(recordReplay);
        // RoomHarness.pvp 默认 1e6 气血（防误杀），这里把 B 调低以便在 1–2 回合内打出真实胜负
        builder.getPlayersBuilder(1).setMaxHealth(100).getBaseAttributesBuilder().setHealth(100);
        return builder;
    }

    private void defendOneRound(long battleId) {
        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND)).build());
        h.service().submit(B, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND)).build());
    }

    private void killBInOneRound(long battleId) {
        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, B)).build());
        h.service().submit(B, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND)).build());
    }

    @Test
    void recordReplay开启时录制观众帧含决胜回合且重试复用同一份冻结字节() throws Exception {
        h.create(pvpV1(43001, true));
        defendOneRound(43001);
        killBInOneRound(43001);

        assertThat(capturedReplays).hasSize(1);
        BattleReplayEnvelope first = capturedReplays.getFirst();
        assertThat(first).isNotNull();
        BattleReplay replay = BattleReplayRecorder.decode(first);
        assertThat(replay.getBattleId()).isEqualTo(43001L);
        assertThat(replay.getTableFingerprint()).isEqualTo(RoomHarness.NODE_FINGERPRINT);
        assertThat(replay.getEndReason()).isEqualTo(eBattleReplayEndReason.BATTLE_REPLAY_END_FINISHED);
        assertThat(replay.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(replay.getTotalRounds()).isEqualTo(2);
        assertThat(replay.getRoundsList()).hasSize(2);
        assertThat(replay.hasFinalState()).isFalse();
        assertThat(replay.getStartState().getSelfItemsList()).isEmpty();
        assertThat(replay.getStartState().getActionDeadlineMs()).isZero();
        assertThat(replay.getStartState().getPendingActorIdsList()).isEmpty();
        assertThat(replay.getStartState().getActorsList()).allSatisfy(actor -> {
            assertThat(actor.hasCombat()).isFalse();
            assertThat(actor.getSkillCooldownRoundsMap()).isEmpty();
        });

        // 首次提交失败重投：必须复用同一个冻结后的回放对象，不重压缩、不产生摘要漂移
        decisions.getFirst().completeExceptionally(new IllegalStateException("match timeout"));
        h.scheduler.runPending();
        h.scheduler.advance(1_000);
        assertThat(capturedReplays).hasSize(2);
        assertThat(capturedReplays.get(1)).isSameAs(first);

        decisions.get(1).complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE);
        h.scheduler.runPending();
        assertThat(h.service().room(43001)).isNull();
    }

    @Test
    void recordReplay关闭时不产生回放载荷() {
        h.create(pvpV1(43002, false));
        killBInOneRound(43002);

        assertThat(capturedReplays).containsExactly((BattleReplayEnvelope) null);
        decisions.getFirst().complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE);
        h.scheduler.runPending();
        assertThat(h.service().room(43002)).isNull();
    }

    @Test
    void 整场期限强制判平写入DEADLINE_DRAW与观众视角final_state() throws Exception {
        h.create(pvpV1(43003, true).setDeadlineMs(h.now() + 1));
        h.scheduler.advance(1);

        assertThat(capturedReplays).hasSize(1);
        BattleReplay replay = BattleReplayRecorder.decode(capturedReplays.getFirst());
        assertThat(replay.getEndReason()).isEqualTo(eBattleReplayEndReason.BATTLE_REPLAY_END_DEADLINE_DRAW);
        assertThat(replay.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        assertThat(replay.hasFinalState()).isTrue();
        assertThat(replay.getFinalState().getActionDeadlineMs()).isZero();
        assertThat(replay.getFinalState().getPendingActorIdsList()).isEmpty();
        assertThat(replay.getFinalState().getSelfItemsList()).isEmpty();

        decisions.getFirst().complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE);
        h.scheduler.runPending();
        assertThat(h.service().room(43003)).isNull();
    }
}
