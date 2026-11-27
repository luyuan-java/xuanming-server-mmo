package com.game.battle.room;

import com.game.battle.engine.WuxingExpansionRules;
import com.game.proto.BattleActorBuildSummary;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleReplay;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleReplayCodec;
import com.game.proto.eBattleReplayEndReason;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单场战斗的观众视角回放录制器（对齐 C++ {@code BattleReplayRecorder} 与 Python {@code battle_replay.py}）。
 *
 * <p>只接受已按观众视角裁剪过的 {@link ViewerState}，并在落帧前剥离实时字段（{@code action_deadline_ms}、
 * {@code pending_actor_ids}）；只在逻辑线程上使用。
 */
final class BattleReplayRecorder {

    private static final Logger log = LoggerFactory.getLogger(BattleReplayRecorder.class);

    static final int FORMAT_VERSION = 1;
    static final int MAX_RAW_BYTES = 1 << 20;
    static final int MAX_PAYLOAD_BYTES = 512 << 10;
    static final int RECORD_BUDGET_BYTES = MAX_RAW_BYTES - (16 << 10);

    private final long battleId;
    private final int recordBudgetBytes;
    private BattleReplay.Builder replay;
    private int recordedBytes;
    private boolean frozen;

    BattleReplayRecorder(CreateBattleRequest request, String tableFingerprint, ViewerState spectatorStartState) {
        this(request, tableFingerprint, spectatorStartState, RECORD_BUDGET_BYTES);
    }

    BattleReplayRecorder(CreateBattleRequest request, String tableFingerprint, ViewerState spectatorStartState,
                         int recordBudgetBytes) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(spectatorStartState, "spectatorStartState");
        this.battleId = request.getBattleId();
        this.recordBudgetBytes = recordBudgetBytes;
        BattleStateS2C start = stripLive(spectatorStartState.state());
        this.replay = BattleReplay.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(request.getMatchMode())
                .setBattleConfigId(request.getBattleConfigId())
                .setStartedAtMs(request.getCreatedAtMs())
                .setTableFingerprint(tableFingerprint == null ? "" : tableFingerprint)
                .setStartState(start);
        int extraBytes = 0;
        boolean usedFreePreset = request.hasRankedContext() && request.getRankedContext().getUseFreePreset();
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            BattleActorBuildSummary.Builder summary = BattleActorBuildSummary.newBuilder()
                    .setPlayerId(snapshot.getPlayerId())
                    .setName(snapshot.getPlayerName())
                    .setTeamIndex(snapshot.getTeamIndex())
                    .setClassId(snapshot.getClassId())
                    .setLevel(snapshot.getLevel())
                    .setFormationId(snapshot.getFormationId())
                    .setWuxingElement(WuxingExpansionRules.elementOfClass(snapshot.getClassId()))
                    .setUsedFreePreset(usedFreePreset);
            if (snapshot.hasEquipmentEffects() && snapshot.getEquipmentEffects().hasSeasonSeal()) {
                summary.setSeasonSealId(snapshot.getEquipmentEffects().getSeasonSeal().getSealId());
            }
            if (snapshot.getPetsCount() > 0) {
                BattlePetSnapshot pet = snapshot.getPets(0);
                int petTableId = pet.getCombatPetTableId() != 0 ? pet.getCombatPetTableId() : pet.getPetTableId();
                summary.setActivePetTableId(petTableId)
                        .setActivePetName(pet.getPetName())
                        .setActivePetElement(WuxingExpansionRules.elementOfPet(petTableId));
            }
            int skillCap = Math.min(12, snapshot.getSkillTableIdsCount());
            for (int i = 0; i < skillCap; i++) {
                summary.addSkillTableIds(snapshot.getSkillTableIds(i));
            }
            if (snapshot.hasTowerContext()) {
                summary.addAllWuxingSigilIds(snapshot.getTowerContext().getActiveSigilIdsList());
            }
            BattleActorBuildSummary built = summary.build();
            this.replay.addActorBuilds(built);
            extraBytes += built.getSerializedSize();
        }
        this.recordedBytes = start.getSerializedSize() + extraBytes;
    }

    /** 录制一回合的观众版帧；超出录制预算时标 truncated 并停止追加后续回合。 */
    void addRound(TurnResultS2C result, ViewerState spectatorState) {
        if (frozen || replay == null || replay.getTruncated()) {
            return;
        }
        TurnResultS2C spectatorTurn = spectatorState.toTurnResult(result);
        TurnResultS2C frame = spectatorTurn.toBuilder()
                .setState(stripLive(spectatorTurn.getState()))
                .build();
        int frameBytes = frame.getSerializedSize();
        if (recordedBytes + frameBytes > recordBudgetBytes) {
            replay.setTruncated(true);
            log.warn("battle replay truncated metric=battle_replay_truncated battle_id={} recorded_rounds={} recorded_bytes={}",
                    Long.toUnsignedString(battleId), replay.getRoundsCount(), recordedBytes);
            return;
        }
        replay.addRounds(frame);
        recordedBytes += frameBytes;
    }

    /** 整场期限到强制判平：记录结束瞬间观众视角快照，end_reason 标为 DEADLINE_DRAW。 */
    void markDeadlineDraw(ViewerState spectatorState) {
        if (frozen || replay == null) {
            return;
        }
        replay.setFinalState(stripLive(spectatorState.state()));
        replay.setEndReason(eBattleReplayEndReason.BATTLE_REPLAY_END_DEADLINE_DRAW);
    }

    /**
     * 终局冻结并做 RFC 1952 gzip（level 1）压缩；最多只冻结一次，之后无论成功或丢弃都清空缓冲。
     */
    Optional<BattleReplayEnvelope> freeze(eBattleOutcome outcome, int totalRounds, long finishedAtMs) {
        if (frozen || replay == null) {
            return Optional.empty();
        }
        frozen = true;
        BattleReplay.Builder builder = replay;
        replay = null;
        recordedBytes = 0;

        builder.setOutcome(outcome)
                .setTotalRounds(totalRounds)
                .setFinishedAtMs(finishedAtMs);
        if (builder.getEndReason() == eBattleReplayEndReason.BATTLE_REPLAY_END_UNSPECIFIED) {
            builder.setEndReason(eBattleReplayEndReason.BATTLE_REPLAY_END_FINISHED);
        }
        BattleReplay built = builder.build();
        int rawBytes = built.getSerializedSize();
        if (rawBytes <= 0 || rawBytes > MAX_RAW_BYTES) {
            log.warn("battle replay dropped metric=battle_replay_dropped reason=raw_too_large battle_id={} raw_bytes={}",
                    Long.toUnsignedString(battleId), rawBytes);
            return Optional.empty();
        }
        byte[] raw = new byte[rawBytes];
        try {
            CodedOutputStream out = CodedOutputStream.newInstance(raw);
            out.useDeterministicSerialization();
            built.writeTo(out);
            out.checkNoSpaceLeft();
        } catch (IOException e) {
            log.warn("battle replay dropped metric=battle_replay_dropped reason=serialize_failed battle_id={}",
                    Long.toUnsignedString(battleId), e);
            return Optional.empty();
        }
        byte[] compressed;
        try {
            compressed = gzipCompress(raw);
        } catch (IOException e) {
            log.warn("battle replay dropped metric=battle_replay_dropped reason=compress_failed battle_id={}",
                    Long.toUnsignedString(battleId), e);
            return Optional.empty();
        }
        if (compressed.length == 0 || compressed.length > MAX_PAYLOAD_BYTES) {
            log.warn("battle replay dropped metric=battle_replay_dropped reason=compressed_too_large battle_id={} raw_bytes={} compressed_bytes={}",
                    Long.toUnsignedString(battleId), rawBytes, compressed.length);
            return Optional.empty();
        }
        log.info("battle replay frozen metric=battle_replay_frozen battle_id={} rounds={} total_rounds={} raw_bytes={} compressed_bytes={} truncated={}",
                Long.toUnsignedString(battleId), built.getRoundsCount(), totalRounds, rawBytes, compressed.length, built.getTruncated());
        return Optional.of(BattleReplayEnvelope.newBuilder()
                .setFormatVersion(FORMAT_VERSION)
                .setCodec(eBattleReplayCodec.BATTLE_REPLAY_CODEC_GZIP)
                .setRawBytes(rawBytes)
                .setPayload(ByteString.copyFrom(compressed))
                .build());
    }

    private static BattleStateS2C stripLive(BattleStateS2C state) {
        return state.toBuilder()
                .clearActionDeadlineMs()
                .clearPendingActorIds()
                .build();
    }

    private static byte[] gzipCompress(byte[] raw) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream(Math.max(256, raw.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(sink) {
            {
                def.setLevel(Deflater.BEST_SPEED);
            }
        }) {
            gzip.write(raw);
        }
        return sink.toByteArray();
    }

    /** 校验外壳并按 raw_bytes 上限有界解压单成员 RFC 1952 gzip（拒绝截断、CRC 错误与尾随字节）。 */
    static BattleReplay decode(BattleReplayEnvelope envelope) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.getFormatVersion() != FORMAT_VERSION
                || envelope.getCodec() != eBattleReplayCodec.BATTLE_REPLAY_CODEC_GZIP
                || envelope.getRawBytes() <= 0
                || envelope.getRawBytes() > MAX_RAW_BYTES
                || envelope.getPayload().isEmpty()
                || envelope.getPayload().size() > MAX_PAYLOAD_BYTES) {
            throw new IOException("invalid battle replay envelope");
        }
        byte[] gz = envelope.getPayload().toByteArray();
        int pos = parseGzipHeader(gz);
        int expected = envelope.getRawBytes();
        Inflater inflater = new Inflater(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        CRC32 crc = new CRC32();
        try {
            inflater.setInput(gz, pos, gz.length - pos);
            byte[] buf = new byte[4096];
            int total = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n > 0) {
                    total += n;
                    if (total > expected) {
                        throw new IOException("battle replay decompressed size exceeds raw_bytes");
                    }
                    crc.update(buf, 0, n);
                    out.write(buf, 0, n);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new IOException("truncated gzip stream");
                }
            }
            int trailerPos = pos + (int) inflater.getBytesRead();
            if (trailerPos + 8 != gz.length) {
                throw new IOException("trailing bytes or truncated gzip trailer");
            }
            if (total != expected) {
                throw new IOException("battle replay decompressed size mismatch: expected=" + expected + " actual=" + total);
            }
            int expectedCrc = readLe32(gz, trailerPos);
            int expectedIsize = readLe32(gz, trailerPos + 4);
            if ((int) crc.getValue() != expectedCrc || total != expectedIsize) {
                throw new IOException("gzip trailer crc/isize mismatch");
            }
        } catch (DataFormatException e) {
            throw new IOException("corrupt gzip deflate stream", e);
        } finally {
            inflater.end();
        }
        return BattleReplay.parseFrom(out.toByteArray());
    }

    private static int parseGzipHeader(byte[] gz) throws IOException {
        if (gz.length < 18 || (gz[0] & 0xFF) != 0x1F || (gz[1] & 0xFF) != 0x8B || (gz[2] & 0xFF) != 8) {
            throw new IOException("invalid gzip header");
        }
        int flg = gz[3] & 0xFF;
        int pos = 10;
        if ((flg & 0x04) != 0) {
            if (pos + 2 > gz.length) throw new IOException("truncated gzip fextra");
            int xlen = (gz[pos] & 0xFF) | ((gz[pos + 1] & 0xFF) << 8);
            pos += 2 + xlen;
        }
        if ((flg & 0x08) != 0) {
            while (pos < gz.length && gz[pos] != 0) pos++;
            pos++;
        }
        if ((flg & 0x10) != 0) {
            while (pos < gz.length && gz[pos] != 0) pos++;
            pos++;
        }
        if ((flg & 0x02) != 0) {
            pos += 2;
        }
        if (pos + 8 > gz.length) {
            throw new IOException("truncated gzip header");
        }
        return pos;
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
