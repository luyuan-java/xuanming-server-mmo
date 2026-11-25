package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActorState;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleReplay;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleReplayCodec;
import com.game.proto.eBattleReplayEndReason;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link BattleReplayRecorder} 往返、跨语言确定性字节对齐、截断、判平与坏包拒绝。 */
class BattleReplayRecorderTest {

    private static CreateBattleRequest sampleRequest() {
        return CreateBattleRequest.newBuilder()
                .setBattleId(9001L)
                .setMatchMode(3)
                .setBattleConfigId(17)
                .setCreatedAtMs(1_700_000_000_000L)
                .setRecordReplay(true)
                .build();
    }

    private static BattleStateS2C sampleState(int roundIndex) {
        return BattleStateS2C.newBuilder()
                .setBattleId(9001L)
                .setRoundIndex(roundIndex)
                .setActionDeadlineMs(1_700_000_006_000L)
                .addPendingActorIds(11L)
                .addActors(BattleActorState.newBuilder()
                        .setActorId(11L)
                        .setName("甲")
                        .setTeamIndex(0)
                        .setMaxHealth(1000)
                        .setAttributes(BaseAttributesComp.newBuilder().setHealth(1000 - roundIndex * 10)))
                .addActors(BattleActorState.newBuilder()
                        .setActorId(22L)
                        .setName("乙")
                        .setTeamIndex(1)
                        .setMaxHealth(1000)
                        .setAttributes(BaseAttributesComp.newBuilder().setHealth(1000 - roundIndex * 10)))
                .build();
    }

    private static TurnResultS2C sampleRound(int roundIndex) {
        TurnResultS2C.Builder builder = TurnResultS2C.newBuilder()
                .setBattleId(9001L)
                .setRoundIndex(roundIndex)
                .addActionOrder(11L)
                .addActionOrder(22L)
                .setState(sampleState(roundIndex));
        builder.addEventsBuilder()
                .setEventType(eBattleEventType.forNumber(3))
                .setSourceId(11L)
                .setTargetId(22L)
                .setValue(10)
                .setTargetHealthAfter(1000 - roundIndex * 10);
        return builder.build();
    }

    @Test
    void 录制与有界解压往返_剥离私有与实时字段_且与CPP和Python确定性字节同源() throws Exception {
        BattleStateS2C.Builder pollutedStart = sampleState(1).toBuilder()
                .addSelfItems(BattleItemEntry.newBuilder().setItemTableId(13).setCount(1));
        pollutedStart.getActorsBuilder(0).getCombatBuilder().setPhysicalCritRate(10);
        pollutedStart.getActorsBuilder(0).putSkillCooldownRounds(15, 2);

        BattleReplayRecorder recorder = new BattleReplayRecorder(
                sampleRequest(), "fingerprint-v1", BattleViews.forObserver(pollutedStart.build()));
        TurnResultS2C r1 = sampleRound(1);
        TurnResultS2C r2 = sampleRound(2);
        recorder.addRound(r1, BattleViews.forObserver(r1.getState()));
        recorder.addRound(r2, BattleViews.forObserver(r2.getState()));

        Optional<BattleReplayEnvelope> frozen = recorder.freeze(
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 2, 1_700_000_012_000L);
        assertThat(frozen).isPresent();
        BattleReplayEnvelope env = frozen.orElseThrow();
        assertThat(env.getFormatVersion()).isEqualTo(BattleReplayRecorder.FORMAT_VERSION);
        assertThat(env.getCodec()).isEqualTo(eBattleReplayCodec.BATTLE_REPLAY_CODEC_GZIP);
        assertThat(env.getPayload().size()).isPositive().isLessThanOrEqualTo(BattleReplayRecorder.MAX_PAYLOAD_BYTES);

        BattleReplay replay = BattleReplayRecorder.decode(env);
        assertThat(replay.getBattleId()).isEqualTo(9001L);
        assertThat(replay.getEndReason()).isEqualTo(eBattleReplayEndReason.BATTLE_REPLAY_END_FINISHED);
        assertThat(replay.hasFinalState()).isFalse();
        assertThat(replay.getRoundsList()).hasSize(2);
        assertThat(replay.getRounds(1).getActionOrderList()).containsExactly(11L, 22L);
        assertThat(replay.getStartState().getActionDeadlineMs()).isZero();
        assertThat(replay.getStartState().getPendingActorIdsList()).isEmpty();
        assertThat(replay.getStartState().getSelfItemsList()).isEmpty();
        assertThat(replay.getStartState().getActors(0).hasCombat()).isFalse();
        assertThat(replay.getStartState().getActors(0).getSkillCooldownRoundsMap()).isEmpty();
        assertThat(replay.getRoundsList()).allSatisfy(frame -> {
            assertThat(frame.getState().getActionDeadlineMs()).isZero();
            assertThat(frame.getState().getPendingActorIdsList()).isEmpty();
        });

        byte[] deterministic = new byte[replay.getSerializedSize()];
        CodedOutputStream out = CodedOutputStream.newInstance(deterministic);
        out.useDeterministicSerialization();
        replay.writeTo(out);
        out.checkNoSpaceLeft();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deterministic));
        assertThat(sha256)
                .as("与 C++ / Python BattleReplayRecorder 相同夹具的未压缩确定性字节 SHA-256 一致")
                .isEqualTo("1674bfae1c65ac49d2c67348a26362225378dce8964ae96bde187a764e453fac");

        assertThat(recorder.freeze(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 2, 1_700_000_012_000L))
                .as("最多只冻结一次")
                .isEmpty();
    }

    @Test
    void 超过预算触发截断并保留真实total_rounds_强制判平写入final_state() throws Exception {
        BattleReplayRecorder recorder = new BattleReplayRecorder(
                sampleRequest(), "fingerprint-v1", BattleViews.forObserver(sampleState(1)), 120);
        TurnResultS2C r1 = sampleRound(1);
        TurnResultS2C r2 = sampleRound(2);
        recorder.addRound(r1, BattleViews.forObserver(r1.getState()));
        recorder.addRound(r2, BattleViews.forObserver(r2.getState()));
        recorder.markDeadlineDraw(BattleViews.forObserver(sampleState(3)));

        BattleReplayEnvelope env = recorder.freeze(
                eBattleOutcome.BATTLE_OUTCOME_DRAW, 2, 1_700_000_300_000L).orElseThrow();
        BattleReplay replay = BattleReplayRecorder.decode(env);
        assertThat(replay.getTruncated()).isTrue();
        assertThat(replay.getTotalRounds()).isEqualTo(2);
        assertThat(replay.getRoundsCount()).isLessThan(2);
        assertThat(replay.getEndReason()).isEqualTo(eBattleReplayEndReason.BATTLE_REPLAY_END_DEADLINE_DRAW);
        assertThat(replay.hasFinalState()).isTrue();
        assertThat(replay.getFinalState().getRoundIndex()).isEqualTo(3);
        assertThat(replay.getFinalState().getActionDeadlineMs()).isZero();
        assertThat(replay.getFinalState().getPendingActorIdsList()).isEmpty();
    }

    @Test
    void 压缩后超过512KiB上限时安全丢弃回放() {
        // 高熵多字节 UTF-8 字符（~840 KiB < 1 MiB 原始上限，gzip 后 > 512 KiB）
        StringBuilder noisy = new StringBuilder(280_000);
        Random rng = new Random(7L);
        for (int i = 0; i < 280_000; i++) {
            noisy.append((char) (0x4E00 + rng.nextInt(0x5000)));
        }
        BattleStateS2C overCompressed = BattleStateS2C.newBuilder()
                .setBattleId(9001L)
                .setRoundIndex(1)
                .addActors(BattleActorState.newBuilder().setActorId(11L).setName(noisy.toString()))
                .build();
        BattleReplayRecorder overRecorder = new BattleReplayRecorder(
                sampleRequest(), "fp", BattleViews.forObserver(overCompressed));
        assertThat(overRecorder.freeze(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 1_700_000_010_000L))
                .as("原始 < 1 MiB 但 gzip > 512 KiB 时应返回 empty 而非抛出异常")
                .isEmpty();
    }

    @Test
    void 解压校验拒绝坏raw_bytes_截断流与尾随垃圾字节() throws Exception {
        BattleReplayRecorder recorder = new BattleReplayRecorder(
                sampleRequest(), "fingerprint-v1", BattleViews.forObserver(sampleState(1)));
        BattleReplayEnvelope valid = recorder.freeze(
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 1_700_000_010_000L).orElseThrow();

        BattleReplayEnvelope wrongRaw = valid.toBuilder().setRawBytes(valid.getRawBytes() - 1).build();
        assertThatThrownBy(() -> BattleReplayRecorder.decode(wrongRaw)).isInstanceOf(IOException.class);

        ByteString truncated = valid.getPayload().substring(0, valid.getPayload().size() - 4);
        BattleReplayEnvelope truncatedEnv = valid.toBuilder().setPayload(truncated).build();
        assertThatThrownBy(() -> BattleReplayRecorder.decode(truncatedEnv)).isInstanceOf(IOException.class);

        ByteString trailing = valid.getPayload().concat(ByteString.copyFrom(new byte[] {0x01, 0x02, 0x03}));
        BattleReplayEnvelope trailingEnv = valid.toBuilder().setPayload(trailing).build();
        assertThatThrownBy(() -> BattleReplayRecorder.decode(trailingEnv)).isInstanceOf(IOException.class);
    }
}
