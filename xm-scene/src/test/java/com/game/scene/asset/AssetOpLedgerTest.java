package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetStream;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpRejectionState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.scene.asset.AssetOpLedger.RecordKind;
import com.game.scene.asset.AssetOpLedger.SeqState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 账本纯逻辑，用例逐条对应 mmorpg asset_op_ledger_test.cpp（窗口、原因环、部分发放、纪元、跳号、加载校验、守卫证明）。 */
class AssetOpLedgerTest {

    private static final int STREAM = AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE;
    private static final long EPOCH = 100;
    private static final int REASON = 27000;
    private static final long MAX = -1L;
    private static final int BITS = AssetOpLedger.WINDOW_BITS;

    private final AssetOpLedger ledger = AssetOpLedger.empty();

    private void record(long seq, RecordKind kind) {
        record(seq, kind, kind == RecordKind.REJECTED ? REASON : 0, EPOCH);
    }

    private void record(long seq, RecordKind kind, int reason) {
        record(seq, kind, reason, EPOCH);
    }

    private void record(long seq, RecordKind kind, int reason, long epoch) {
        assertThat(ledger.record(STREAM, epoch, seq, kind, reason)).as("record seq=%s", Long.toUnsignedString(seq))
                .isTrue();
    }

    private SeqState classify(long seq) {
        return ledger.classify(STREAM, EPOCH, seq);
    }

    private AssetOpStreamLedgerState stream() {
        return ledger.toState().getStreams(0);
    }

    private static AssetOpStreamLedgerState.Builder raw(long epoch, long watermark, long maxSeq) {
        AssetOpStreamLedgerState.Builder b = AssetOpStreamLedgerState.newBuilder().setStream(STREAM)
                .setStreamEpoch(epoch).setWatermark(watermark).setMaxSeq(maxSeq);
        for (int i = 0; i < 16; i++) {
            b.addSeenBits(0).addAppliedBits(0);
        }
        return b;
    }

    private static AssetOpLedger restore(AssetOpStreamLedgerState.Builder... streams) {
        AssetOpLedgerState.Builder state = AssetOpLedgerState.newBuilder();
        for (AssetOpStreamLedgerState.Builder s : streams) {
            state.addStreams(s);
        }
        return AssetOpLedger.restore(state.build());
    }

    // ------------------------------------------------------------------ 基础

    @Test
    void 空账本分类() {
        assertThat(classify(1)).isEqualTo(SeqState.UNSEEN);
        assertThat(classify(BITS)).isEqualTo(SeqState.UNSEEN);
        assertThat(classify(BITS + 1)).as("纪元 0 的空账本按纪元更大看：seq > 1024 即跳号").isEqualTo(SeqState.JUMP_TOO_FAR);
        assertThat(classify(0)).isEqualTo(SeqState.INVALID);
        assertThat(ledger.classify(STREAM, 0, 1)).isEqualTo(SeqState.INVALID);
        assertThat(ledger.isPristine()).isTrue();
        assertThat(ledger.toState()).isEqualTo(AssetOpLedgerState.getDefaultInstance());
    }

    @Test
    void 记应用与拒绝() {
        record(3, RecordKind.APPLIED);
        record(5, RecordKind.REJECTED);
        assertThat(classify(3)).isEqualTo(SeqState.APPLIED);
        assertThat(classify(5)).isEqualTo(SeqState.REJECTED);
        assertThat(classify(4)).isEqualTo(SeqState.UNSEEN);
        assertThat(ledger.rejectionReason(STREAM, 5)).isEqualTo(REASON);
        assertThat(ledger.rejectionReason(STREAM, 3)).isZero();
        assertThat(stream().getMaxSeq()).isEqualTo(5);
        assertThat(stream().getWatermark()).isZero();
        assertThat(stream().getStreamEpoch()).isEqualTo(EPOCH);
        assertThat(ledger.isPristine()).isFalse();
    }

    @Test
    void applied始终是seen的子集() {
        for (long seq = 1; seq <= 200; seq++) {
            RecordKind kind = seq % 3 == 0 ? RecordKind.REJECTED
                    : seq % 7 == 0 ? RecordKind.APPLIED_PARTIAL : RecordKind.APPLIED;
            record(seq, kind);
            AssetOpStreamLedgerState s = stream();
            for (int word = 0; word < 16; word++) {
                assertThat(s.getAppliedBits(word) & ~s.getSeenBits(word)).isZero();
            }
        }
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    @Test
    void 位下标边界() {
        record(1, RecordKind.APPLIED);
        assertThat(stream().getSeenBits(0)).isEqualTo(1L);
        record(BITS, RecordKind.REJECTED);
        assertThat(stream().getSeenBits(15)).isEqualTo(1L << 63);
        assertThat(stream().getAppliedBits(15)).isZero();
        assertThat(classify(1)).isEqualTo(SeqState.APPLIED);
        assertThat(classify(BITS)).isEqualTo(SeqState.REJECTED);
        assertThat(classify(2)).isEqualTo(SeqState.UNSEEN);
        assertThat(classify(BITS - 1)).isEqualTo(SeqState.UNSEEN);
        for (int word = 1; word < 15; word++) {
            assertThat(stream().getSeenBits(word)).isZero();
        }
    }

    // ------------------------------------------------------------------ 窗口滑动

    @Test
    void 滑一位() {
        for (long seq = 1; seq <= BITS; seq++) {
            record(seq, RecordKind.APPLIED);
        }
        assertThat(classify(BITS + 1)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        record(BITS + 1, RecordKind.APPLIED);
        assertThat(stream().getWatermark()).isEqualTo(1);
        assertThat(stream().getMaxSeq()).isEqualTo(BITS + 1);
        assertThat(classify(1)).isEqualTo(SeqState.BEHIND_WINDOW);
        assertThat(classify(2)).isEqualTo(SeqState.APPLIED);
        assertThat(classify(BITS)).isEqualTo(SeqState.APPLIED);
        assertThat(classify(BITS + 1)).isEqualTo(SeqState.APPLIED);
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    @Test
    void 正好滑1024位_旧位全丢() {
        record(BITS, RecordKind.APPLIED);
        assertThat(classify(2L * BITS)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        record(2L * BITS, RecordKind.APPLIED);
        assertThat(stream().getWatermark()).isEqualTo(BITS);
        assertThat(classify(1)).isEqualTo(SeqState.BEHIND_WINDOW);
        assertThat(classify(BITS)).isEqualTo(SeqState.BEHIND_WINDOW);
        assertThat(classify(2L * BITS)).isEqualTo(SeqState.APPLIED);
        assertThat(stream().getSeenBits(15)).isEqualTo(1L << 63);
        for (int word = 0; word < 15; word++) {
            assertThat(stream().getSeenBits(word)).isZero();
        }
    }

    @Test
    void 滑出整窗_全清() {
        // 真实流里跳号上限让 shift > 1024 不可达；手工抬高 max_seq 覆盖这条防御分支
        AssetOpLedger l = restore(raw(EPOCH, 0, 4000).setSeenBits(0, 1).setAppliedBits(0, 1));
        assertThat(l.classify(STREAM, EPOCH, 5000)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(l.record(STREAM, EPOCH, 5000, RecordKind.APPLIED, 0)).isTrue();
        AssetOpStreamLedgerState s = l.toState().getStreams(0);
        assertThat(s.getWatermark()).isEqualTo(5000 - BITS);
        assertThat(l.classify(STREAM, EPOCH, 1)).isEqualTo(SeqState.BEHIND_WINDOW);
        assertThat(l.classify(STREAM, EPOCH, 5000)).isEqualTo(SeqState.APPLIED);
        assertThat(s.getSeenBits(15)).isEqualTo(1L << 63);
        for (int word = 0; word < 15; word++) {
            assertThat(s.getSeenBits(word)).isZero();
        }
    }

    @Test
    void 滑窗剪掉窗口外的拒绝原因() {
        record(2, RecordKind.REJECTED);
        record(BITS, RecordKind.APPLIED);
        assertThat(stream().getRejectionsCount()).isEqualTo(1);
        record(BITS + 6, RecordKind.APPLIED);
        assertThat(stream().getWatermark()).isEqualTo(6);
        assertThat(stream().getRejectionsCount()).isZero();
        assertThat(ledger.rejectionReason(STREAM, 2)).isZero();
        assertThat(classify(2)).isEqualTo(SeqState.BEHIND_WINDOW);
        assertThat(classify(BITS)).isEqualTo(SeqState.APPLIED);
        assertThat(classify(BITS + 6)).isEqualTo(SeqState.APPLIED);
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    @Test
    void 移位按1024位大整数整体右移() {
        long[] words = new long[16];
        words[0] = 0b1011;
        words[1] = 1L;
        words[15] = 1L << 63;
        AssetOpLedger.shiftRight(words, 1);
        assertThat(words[0]).isEqualTo(0b101L | (1L << 63));
        assertThat(words[1]).isZero();
        assertThat(words[14]).isZero();
        assertThat(words[15]).as("位 1023 → 1022，仍在第 15 个字").isEqualTo(1L << 62);
        AssetOpLedger.shiftRight(words, 64 * 14 + 63);
        assertThat(words[0]).as("位 1022 → 63，低位全部丢弃").isEqualTo(1L << 63);
        for (int word = 1; word < 16; word++) {
            assertThat(words[word]).isZero();
        }
    }

    // ------------------------------------------------------------------ 原因环

    @Test
    void 原因环上限64_挤掉最小的_结局不变() {
        for (long seq = 1; seq <= 70; seq++) {
            record(seq, RecordKind.REJECTED, REASON + (int) seq);
        }
        List<AssetOpRejectionState> ring = stream().getRejectionsList();
        assertThat(ring).hasSize(AssetOpLedger.MAX_REJECTIONS);
        assertThat(ring.get(0).getSeq()).isEqualTo(7);
        assertThat(ring.get(63).getSeq()).isEqualTo(70);
        assertThat(ledger.rejectionReason(STREAM, 1)).isZero();
        assertThat(classify(1)).isEqualTo(SeqState.REJECTED);
        assertThat(ledger.rejectionReason(STREAM, 7)).isEqualTo(REASON + 7);
    }

    @Test
    void 中止占位不占原因环() {
        record(1, RecordKind.REJECTED, 0);
        record(2, RecordKind.REJECTED, REASON);
        assertThat(stream().getRejectionsList()).extracting(AssetOpRejectionState::getSeq).containsExactly(2L);
        assertThat(classify(1)).as("占位照样是拒绝").isEqualTo(SeqState.REJECTED);
        assertThat(ledger.rejectionReason(STREAM, 1)).isZero();
        for (long seq = 3; seq <= 128; seq++) {
            record(seq, RecordKind.REJECTED, seq % 2 == 1 ? 0 : REASON + (int) seq);
        }
        List<AssetOpRejectionState> ring = stream().getRejectionsList();
        assertThat(ring).hasSize(64);
        assertThat(ring.get(0).getSeq()).as("63 个占位挤不掉任何业务拒绝").isEqualTo(2);
        assertThat(ring.get(63).getSeq()).isEqualTo(128);
        assertThat(ledger.rejectionReason(STREAM, 127)).isZero();
        assertThat(classify(127)).isEqualTo(SeqState.REJECTED);
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    @Test
    void 被挤掉的拒绝原因退化成0_与中止占位不可区分_刻意接受() {
        for (long seq = 1; seq <= 65; seq++) {
            record(seq, RecordKind.REJECTED, REASON + (int) seq);
        }
        assertThat(classify(1)).isEqualTo(SeqState.REJECTED);
        assertThat(ledger.rejectionReason(STREAM, 1)).isZero();
        record(200, RecordKind.REJECTED, 0);
        assertThat(classify(200)).isEqualTo(classify(1));
        assertThat(ledger.rejectionReason(STREAM, 200)).isEqualTo(ledger.rejectionReason(STREAM, 1));
    }

    @Test
    void 乱序记拒绝_环保持升序() {
        record(10, RecordKind.REJECTED, REASON + 10);
        record(3, RecordKind.REJECTED, REASON + 3);
        record(7, RecordKind.REJECTED, REASON + 7);
        assertThat(stream().getRejectionsList()).extracting(AssetOpRejectionState::getSeq).containsExactly(3L, 7L, 10L);
        assertThat(ledger.rejectionReason(STREAM, 7)).isEqualTo(REASON + 7);
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    // ------------------------------------------------------------------ 部分发放

    @Test
    void 部分发放在位图上同应用_另进名单() {
        record(1, RecordKind.APPLIED_PARTIAL);
        record(2, RecordKind.APPLIED);
        assertThat(classify(1)).isEqualTo(SeqState.APPLIED);
        assertThat(classify(2)).isEqualTo(SeqState.APPLIED);
        assertThat(ledger.isPartial(STREAM, 1)).isTrue();
        assertThat(ledger.isPartial(STREAM, 2)).isFalse();
        assertThat(stream().getPartialSeqsList()).containsExactly(1L);
    }

    @Test
    void 部分发放名单上限64_滑窗剪枝() {
        for (long seq = 1; seq <= 70; seq++) {
            record(seq, RecordKind.APPLIED_PARTIAL);
        }
        assertThat(stream().getPartialSeqsList()).hasSize(64);
        assertThat(stream().getPartialSeqs(0)).isEqualTo(7);
        assertThat(ledger.isPartial(STREAM, 1)).isFalse();
        assertThat(ledger.isPartial(STREAM, 7)).isTrue();
        record(70 + BITS, RecordKind.APPLIED);
        assertThat(stream().getWatermark()).isEqualTo(70);
        assertThat(stream().getPartialSeqsCount()).isZero();
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    // ------------------------------------------------------------------ 前置与边界

    @Test
    void 前置不满足_拒记且什么都不改() {
        record(3, RecordKind.APPLIED);
        record(5, RecordKind.REJECTED);
        AssetOpLedgerState before = ledger.toState();
        assertThat(ledger.record(STREAM, EPOCH, 3, RecordKind.REJECTED, REASON)).isFalse();
        assertThat(ledger.record(STREAM, EPOCH, 5, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.record(STREAM, EPOCH, 0, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.record(STREAM, 0, 9, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.record(STREAM, EPOCH - 1, 9, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.record(STREAM, EPOCH, 5 + AssetOpLedger.MAX_SEQ_JUMP + 1, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.toState()).isEqualTo(before);
        record(BITS + 5, RecordKind.APPLIED);
        assertThat(classify(3)).isEqualTo(SeqState.BEHIND_WINDOW);
        assertThat(ledger.record(STREAM, EPOCH, 3, RecordKind.APPLIED, 0)).isFalse();
    }

    @Test
    void 前置失败不会留下纪元0的空流() {
        assertThat(ledger.record(STREAM, EPOCH, BITS + 1, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.isPristine()).isTrue();
        assertThat(ledger.toState().getStreamsCount()).isZero();
    }

    @Test
    void watermark接近溢出_加载即判损坏_分类恒INVALID() {
        AssetOpLedger l = restore(raw(EPOCH, MAX - 1000, MAX - 1000));
        assertThat(l.invalidReason()).contains("watermark 接近溢出");
        assertThat(l.classify(STREAM, EPOCH, MAX - 999)).isEqualTo(SeqState.INVALID);
        assertThat(l.classify(STREAM, EPOCH, 1)).isEqualTo(SeqState.INVALID);
        assertThat(l.record(STREAM, EPOCH, MAX - 999, RecordKind.APPLIED, 0)).isFalse();
    }

    @Test
    void max_seq接近uint64上限时跳号上限不可表示_不判跳号() {
        AssetOpLedger l = restore(raw(EPOCH, 0, MAX - 1));
        assertThat(l.invalidReason()).isNull();
        assertThat(l.classify(STREAM, EPOCH, 2000)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(l.classify(STREAM, EPOCH, MAX)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(l.classify(STREAM, EPOCH, BITS)).isEqualTo(SeqState.UNSEEN);
        AssetOpLedger edge = restore(raw(EPOCH, 0, MAX - AssetOpLedger.MAX_SEQ_JUMP));
        assertThat(edge.classify(STREAM, EPOCH, MAX)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(edge.classify(STREAM, EPOCH, 2000)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
    }

    @Test
    void 跳号上限溢出守卫的三个相邻取值() {
        long jump = AssetOpLedger.MAX_SEQ_JUMP;
        AssetOpLedger first = restore(raw(EPOCH, 0, MAX - jump + 1));
        assertThat(first.invalidReason()).isNull();
        assertThat(first.classify(STREAM, EPOCH, MAX)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(first.classify(STREAM, EPOCH, MAX - jump)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(first.classify(STREAM, EPOCH, BITS)).isEqualTo(SeqState.UNSEEN);
        assertThat(restore(raw(EPOCH, 0, MAX - jump)).classify(STREAM, EPOCH, MAX)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        AssetOpLedger third = restore(raw(EPOCH, 0, MAX - jump - 1));
        assertThat(third.classify(STREAM, EPOCH, MAX)).isEqualTo(SeqState.JUMP_TOO_FAR);
        assertThat(third.classify(STREAM, EPOCH, MAX - 1)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
    }

    @Test
    void 流按流号升序_每流一条() {
        assertThat(ledger.record(AssetStream.ASSET_STREAM_SYSTEM_CREDIT_VALUE, EPOCH, 1, RecordKind.APPLIED, 0)).isTrue();
        assertThat(ledger.record(AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE, EPOCH, 1, RecordKind.APPLIED, 0)).isTrue();
        assertThat(ledger.record(AssetStream.ASSET_STREAM_TRADE_DEBIT_VALUE, EPOCH, 1, RecordKind.APPLIED, 0)).isTrue();
        assertThat(ledger.record(AssetStream.ASSET_STREAM_SYSTEM_CREDIT_VALUE, EPOCH, 2, RecordKind.APPLIED, 0)).isTrue();
        assertThat(ledger.toState().getStreamsList()).extracting(AssetOpStreamLedgerState::getStream)
                .containsExactly(1, 3, 5);
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }

    // ------------------------------------------------------------------ 流纪元与跳号上限

    @Test
    void 纪元为0非法() {
        record(1, RecordKind.APPLIED);
        assertThat(ledger.classify(STREAM, 0, 1)).isEqualTo(SeqState.INVALID);
    }

    @Test
    void 纪元更大_整条流重置() {
        for (long seq = 1; seq <= 3; seq++) {
            record(seq, RecordKind.REJECTED);
        }
        assertThat(ledger.classify(STREAM, 200, 1)).isEqualTo(SeqState.UNSEEN);
        record(1, RecordKind.APPLIED, 0, 200);
        assertThat(stream().getWatermark()).isZero();
        assertThat(stream().getMaxSeq()).isEqualTo(1);
        assertThat(stream().getStreamEpoch()).isEqualTo(200);
        assertThat(stream().getRejectionsCount()).isZero();
        assertThat(ledger.classify(STREAM, 200, 1)).isEqualTo(SeqState.APPLIED);
        assertThat(ledger.classify(STREAM, 200, 2)).isEqualTo(SeqState.UNSEEN);
    }

    @Test
    void 纪元更大但跳号过远_拒记且不先重置() {
        record(1, RecordKind.APPLIED);
        AssetOpLedgerState before = ledger.toState();
        assertThat(ledger.classify(STREAM, 200, BITS + 1)).isEqualTo(SeqState.JUMP_TOO_FAR);
        assertThat(ledger.record(STREAM, 200, BITS + 1, RecordKind.APPLIED, 0)).isFalse();
        assertThat(ledger.toState()).isEqualTo(before);
        assertThat(classify(1)).isEqualTo(SeqState.APPLIED);
    }

    @Test
    void 旧纪元() {
        record(1, RecordKind.APPLIED, 0, 200);
        assertThat(ledger.classify(STREAM, EPOCH, 1)).isEqualTo(SeqState.STALE_EPOCH);
        assertThat(ledger.classify(STREAM, EPOCH, 9)).isEqualTo(SeqState.STALE_EPOCH);
        assertThat(ledger.record(STREAM, EPOCH, 9, RecordKind.APPLIED, 0)).isFalse();
        assertThat(stream().getStreamEpoch()).isEqualTo(200);
    }

    @Test
    void 纪元按无符号比较() {
        record(1, RecordKind.APPLIED, 0, MAX);
        assertThat(ledger.classify(STREAM, 1, 1)).as("2^64-1 是最大纪元").isEqualTo(SeqState.STALE_EPOCH);
        assertThat(ledger.classify(STREAM, MAX, 1)).isEqualTo(SeqState.APPLIED);
    }

    @Test
    void 同纪元跳号上限() {
        record(10, RecordKind.APPLIED);
        assertThat(classify(1024)).isEqualTo(SeqState.UNSEEN);
        assertThat(classify(1034)).isEqualTo(SeqState.AHEAD_OF_WINDOW);
        assertThat(classify(1035)).isEqualTo(SeqState.JUMP_TOO_FAR);
        assertThat(ledger.record(STREAM, EPOCH, 1035, RecordKind.APPLIED, 0)).isFalse();
        assertThat(stream().getMaxSeq()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ 加载校验与往返

    private static AssetOpStreamLedgerState.Builder valid() {
        // seq 1 应用（部分）、seq 2 拒绝、seq 3 应用
        return raw(EPOCH, 0, 3).setSeenBits(0, 0b111).setAppliedBits(0, 0b101)
                .addRejections(AssetOpRejectionState.newBuilder().setSeq(2).setReasonTipId(REASON))
                .addPartialSeqs(1);
    }

    @Test
    void 合法账本往返不变() {
        AssetOpLedgerState state = AssetOpLedgerState.newBuilder().addStreams(valid())
                .addStreams(valid().setStream(AssetStream.ASSET_STREAM_TRADE_CREDIT_VALUE)).build();
        AssetOpLedger l = AssetOpLedger.restore(state);
        assertThat(l.invalidReason()).isNull();
        assertThat(l.toState()).isEqualTo(state);
        assertThat(l.classify(STREAM, EPOCH, 1)).isEqualTo(SeqState.APPLIED);
        assertThat(l.isPartial(STREAM, 1)).isTrue();
        assertThat(l.classify(STREAM, EPOCH, 2)).isEqualTo(SeqState.REJECTED);
        assertThat(l.rejectionReason(STREAM, 2)).isEqualTo(REASON);
        assertThat(AssetOpLedger.validate(AssetOpLedgerState.getDefaultInstance())).isNull();
    }

    private record Broken(AssetOpStreamLedgerState.Builder stream, String reason) {
    }

    private static AssetOpRejectionState.Builder rejection(long seq) {
        return AssetOpRejectionState.newBuilder().setSeq(seq).setReasonTipId(REASON);
    }

    @Test
    void 各种损坏都判出来_每条规则单独钉住_原样带回不改写() {
        List<Broken> cases = List.of(
                new Broken(valid().clearSeenBits().addAllSeenBits(Collections.nCopies(15, 0L)), "seen_bits 数量=15"),
                new Broken(valid().addAppliedBits(0), "applied_bits 数量=17"),
                new Broken(valid().setAppliedBits(1, 1), "applied 不是 seen 的子集"),
                new Broken(valid().setStream(0), "不是合法流"),
                new Broken(valid().setStream(99), "不是合法流"),
                new Broken(valid().setStreamEpoch(0), "stream_epoch = 0"),
                new Broken(raw(EPOCH, MAX - 1000, MAX - 1000), "watermark 接近溢出"),
                new Broken(raw(EPOCH, 100, 99), "max_seq=99 < watermark=100"),
                new Broken(valid().addRejections(rejection(BITS + 1)), "rejections 越窗"),
                new Broken(raw(EPOCH, 10, 20).addRejections(rejection(5)), "rejections 越窗"),
                new Broken(valid().setRejections(0, rejection(1)), "已见且未应用"),
                new Broken(valid().addRejections(rejection(4)), "已见且未应用"),
                new Broken(valid().addRejections(rejection(2)), "rejections 未严格升序"),
                new Broken(valid().addPartialSeqs(2), "partial_seqs 对应位不是 applied"),
                new Broken(valid().addPartialSeqs(BITS + 9), "partial_seqs 越窗"),
                new Broken(raw(EPOCH, 10, 20).addPartialSeqs(5), "partial_seqs 越窗"),
                new Broken(valid().addPartialSeqs(1), "partial_seqs 未严格升序"));
        for (Broken c : cases) {
            AssetOpLedgerState state = AssetOpLedgerState.newBuilder().addStreams(c.stream()).build();
            AssetOpLedger l = AssetOpLedger.restore(state);
            assertThat(l.invalidReason()).as(c.reason()).contains(c.reason());
            assertThat(l.toState()).isEqualTo(state);
            assertThat(l.isPristine()).isFalse();
            assertThat(l.record(STREAM, EPOCH, 4, RecordKind.APPLIED, 0)).isFalse();
            assertThat(l.classify(STREAM, EPOCH, 1)).isEqualTo(SeqState.INVALID);
        }
        AssetOpLedgerState dup = AssetOpLedgerState.newBuilder().addStreams(valid()).addStreams(valid()).build();
        assertThat(AssetOpLedger.validate(dup)).contains("未严格升序");
        AssetOpLedgerState unordered = AssetOpLedgerState.newBuilder()
                .addStreams(valid().setStream(AssetStream.ASSET_STREAM_TRADE_DEBIT_VALUE)).addStreams(valid()).build();
        assertThat(AssetOpLedger.validate(unordered)).contains("未严格升序");
    }

    @Test
    void 超过64条的原因环与部分名单判损坏() {
        AssetOpStreamLedgerState.Builder rejections = raw(EPOCH, 0, 65);
        AssetOpStreamLedgerState.Builder partials = raw(EPOCH, 0, 65);
        for (int i = 0; i < 2; i++) {
            rejections.setSeenBits(i, -1L);
            partials.setSeenBits(i, -1L).setAppliedBits(i, -1L);
        }
        for (long seq = 1; seq <= 65; seq++) {
            rejections.addRejections(rejection(seq));
            partials.addPartialSeqs(seq);
        }
        assertThat(AssetOpLedger.validate(AssetOpLedgerState.newBuilder().addStreams(rejections).build()))
                .contains("rejections 数量=65");
        assertThat(AssetOpLedger.validate(AssetOpLedgerState.newBuilder().addStreams(partials).build()))
                .contains("partial_seqs 数量=65");
    }

    @Test
    void 各层不认识的字段原样带回_记账之后也不丢() {
        UnknownFieldSet extra = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build();
        AssetOpStreamLedgerState.Builder stream = valid().setUnknownFields(extra);
        stream.setRejections(0, stream.getRejections(0).toBuilder().setUnknownFields(extra));
        AssetOpLedgerState state = AssetOpLedgerState.newBuilder().addStreams(stream).setUnknownFields(extra).build();

        AssetOpLedger l = AssetOpLedger.restore(state);
        assertThat(l.invalidReason()).isNull();
        assertThat(l.toState()).isEqualTo(state);
        assertThat(l.record(STREAM, EPOCH, 4, RecordKind.REJECTED, REASON + 4)).isTrue();
        AssetOpLedgerState after = l.toState();
        assertThat(after.getUnknownFields()).isEqualTo(extra);
        assertThat(after.getStreams(0).getUnknownFields()).isEqualTo(extra);
        assertThat(after.getStreams(0).getRejections(0).getUnknownFields()).isEqualTo(extra);
        assertThat(after.getStreams(0).getRejections(1).getSeq()).isEqualTo(4);
    }


    // ------------------------------------------------------------------ 守卫证明（基线 LedgerGapGuardProof）

    @Test
    void 未决seq永远在窗口内_已记账的结局永远不变() {
        int maxPending = 16;
        long maxSpan = 512;
        SplittableRandom rng = new SplittableRandom(20260917);
        long nextSeq = 1;
        TreeMap<Long, Boolean> pending = new TreeMap<>();
        TreeMap<Long, SeqState> outcomes = new TreeMap<>();
        int allocated = 0;
        int recorded = 0;
        for (int step = 0; step < 10_000; step++) {
            switch (rng.nextInt(3)) {
                case 0 -> {
                    boolean spanOk = pending.isEmpty() || nextSeq - pending.firstKey() < maxSpan;
                    if (pending.size() >= maxPending || !spanOk) {
                        break;
                    }
                    SeqState state = classify(nextSeq);
                    assertThat(state).isNotIn(SeqState.JUMP_TOO_FAR, SeqState.BEHIND_WINDOW);
                    pending.put(nextSeq++, false);
                    allocated++;
                }
                case 1 -> {
                    List<Long> candidates = pending.entrySet().stream().filter(e -> !e.getValue()).map(e -> e.getKey())
                            .toList();
                    if (candidates.isEmpty()) {
                        break;
                    }
                    long seq = candidates.get(rng.nextInt(candidates.size()));
                    assertThat(classify(seq)).isIn(SeqState.UNSEEN, SeqState.AHEAD_OF_WINDOW);
                    boolean applied = rng.nextBoolean();
                    record(seq, applied ? RecordKind.APPLIED : RecordKind.REJECTED);
                    outcomes.put(seq, applied ? SeqState.APPLIED : SeqState.REJECTED);
                    pending.put(seq, true);
                    recorded++;
                }
                default -> {
                    List<Long> candidates = pending.entrySet().stream().filter(e -> e.getValue()).map(e -> e.getKey())
                            .toList();
                    if (!candidates.isEmpty()) {
                        pending.remove(candidates.get(rng.nextInt(candidates.size())));
                    }
                }
            }
            for (var e : pending.entrySet()) {
                SeqState state = classify(e.getKey());
                assertThat(state).as("step=%d seq=%d", step, e.getKey())
                        .isNotIn(SeqState.BEHIND_WINDOW, SeqState.JUMP_TOO_FAR);
                if (e.getValue()) {
                    assertThat(state).isEqualTo(outcomes.get(e.getKey()));
                }
            }
        }
        assertThat(allocated).isGreaterThan(500);
        assertThat(recorded).isGreaterThan(500);
        assertThat(AssetOpLedger.validate(ledger.toState())).isNull();
    }
}
