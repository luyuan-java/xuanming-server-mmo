package com.game.player.store.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpRejectionState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.PlayerState;
import org.junit.jupiter.api.Test;

/**
 * 已落盘账本的只读分类（基线 {@code classify_test.go} 的 ClassifyPersisted 部分）。窗口 / 跳号 / 纪元 / 加载校验的整张用例表在 xm-scene 的
 * {@code AssetOpLedgerTest}（在线账本与本类共用 {@link AssetLedgerRules}，那边的用例同时钉住这份代码）；这里钉只读视图自己的契约。
 */
class PersistedAssetLedgerTest {

    private static final int GUILD_DEBIT = 1;
    private static final int GUILD_CREDIT = 2;
    private static final long EPOCH = 1_700_000_000_000L;
    private static final long MAX = -1L;

    private static AssetOpStreamLedgerState.Builder raw(int stream, long epoch, long watermark, long maxSeq) {
        AssetOpStreamLedgerState.Builder b = AssetOpStreamLedgerState.newBuilder().setStream(stream)
                .setStreamEpoch(epoch).setWatermark(watermark).setMaxSeq(maxSeq);
        for (int i = 0; i < AssetLedgerRules.WINDOW_WORDS; i++) {
            b.addSeenBits(0).addAppliedBits(0);
        }
        return b;
    }

    /** seq 1 部分发放、seq 2 拒绝（27000）、seq 3 应用、seq 4 中止占位（拒绝、原因 0）。 */
    private static AssetOpStreamLedgerState.Builder sample(int stream) {
        return raw(stream, EPOCH, 0, 4).setSeenBits(0, 0b1111).setAppliedBits(0, 0b0101)
                .addRejections(AssetOpRejectionState.newBuilder().setSeq(2).setReasonTipId(27000))
                .addPartialSeqs(1);
    }

    private static PersistedAssetLedger ledger(AssetOpStreamLedgerState.Builder... streams) {
        AssetOpLedgerState.Builder state = AssetOpLedgerState.newBuilder();
        for (AssetOpStreamLedgerState.Builder s : streams) {
            state.addStreams(s);
        }
        return PersistedAssetLedger.restore(state.build());
    }

    @Test
    void 已应用已拒绝是结论_部分发放与拒绝原因带出来() {
        PersistedAssetLedger l = ledger(sample(GUILD_DEBIT));
        assertThat(l.invalidReason()).isNull();
        assertThat(l.outcomeOf(GUILD_DEBIT, EPOCH, 1)).isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.APPLIED, true, 0));
        assertThat(l.outcomeOf(GUILD_DEBIT, EPOCH, 2))
                .isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.REJECTED, false, 27000));
        assertThat(l.outcomeOf(GUILD_DEBIT, EPOCH, 3)).isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.APPLIED, false, 0));
        assertThat(l.outcomeOf(GUILD_DEBIT, EPOCH, 4)).as("中止占位：拒绝、原因 0")
                .isEqualTo(new PersistedAssetLedger.Outcome(AssetSeqState.REJECTED, false, 0));
        for (long seq = 1; seq <= 4; seq++) {
            assertThat(l.outcomeOf(GUILD_DEBIT, EPOCH, seq).conclusive()).isTrue();
        }
        assertThat(l.epochOf(GUILD_DEBIT)).isEqualTo(EPOCH);
    }

    @Test
    void 未见与各种不可采信都不是结论_绝不据此判中止() {
        PersistedAssetLedger l = ledger(sample(GUILD_DEBIT));
        record Case(long epoch, long seq, AssetSeqState state) {
        }
        for (Case c : new Case[] {
                new Case(EPOCH, 5, AssetSeqState.UNSEEN),
                new Case(EPOCH, 1024, AssetSeqState.UNSEEN),
                new Case(EPOCH, 1025, AssetSeqState.AHEAD_OF_WINDOW),
                new Case(EPOCH, 4 + 1025, AssetSeqState.JUMP_TOO_FAR),
                new Case(EPOCH - 1, 1, AssetSeqState.STALE_EPOCH),
                new Case(EPOCH + 1, 1, AssetSeqState.UNSEEN),
                new Case(EPOCH + 1, 1025, AssetSeqState.JUMP_TOO_FAR),
                new Case(EPOCH, 0, AssetSeqState.INVALID),
                new Case(0, 1, AssetSeqState.INVALID)}) {
            PersistedAssetLedger.Outcome outcome = l.outcomeOf(GUILD_DEBIT, c.epoch(), c.seq());
            assertThat(outcome.state()).as(c.toString()).isEqualTo(c.state());
            assertThat(outcome.conclusive()).as(c.toString()).isFalse();
        }
        assertThat(l.outcomeOf(GUILD_CREDIT, EPOCH, 1).state()).as("没有这条流 = 空账本").isEqualTo(AssetSeqState.UNSEEN);
        assertThat(ledger(raw(GUILD_DEBIT, EPOCH, 100, 200)).classify(GUILD_DEBIT, EPOCH, 100))
                .isEqualTo(AssetSeqState.BEHIND_WINDOW);
    }

    @Test
    void 纪元与seq按无符号比较() {
        PersistedAssetLedger l = ledger(raw(GUILD_DEBIT, MAX, 0, 1).setSeenBits(0, 1).setAppliedBits(0, 1));
        assertThat(l.classify(GUILD_DEBIT, MAX, 1)).isEqualTo(AssetSeqState.APPLIED);
        assertThat(l.classify(GUILD_DEBIT, 1, 1)).as("2^64-1 是最大纪元").isEqualTo(AssetSeqState.STALE_EPOCH);
        assertThat(ledger(raw(GUILD_DEBIT, EPOCH, 0, MAX - 1)).classify(GUILD_DEBIT, EPOCH, MAX))
                .as("max_seq 接近 uint64 上限：跳号上限不可表示，不判跳号").isEqualTo(AssetSeqState.AHEAD_OF_WINDOW);
    }

    @Test
    void 损坏账本_原因可读_分类恒INVALID_不是结论() {
        PersistedAssetLedger l = ledger(sample(GUILD_DEBIT).setAppliedBits(1, 1));
        assertThat(l.invalidReason()).contains("applied 不是 seen 的子集");
        assertThat(l.classify(GUILD_DEBIT, EPOCH, 1)).isEqualTo(AssetSeqState.INVALID);
        assertThat(l.outcomeOf(GUILD_DEBIT, EPOCH, 3).conclusive()).isFalse();
        assertThat(l.epochOf(GUILD_DEBIT)).isZero();
        assertThat(ledger(sample(9)).invalidReason()).contains("不是合法流");
        assertThat(ledger(sample(GUILD_CREDIT), sample(GUILD_DEBIT)).invalidReason()).contains("未严格升序");
    }

    @Test
    void 没有账本段与空账本都是空账本() {
        assertThat(PersistedAssetLedger.of(PlayerState.getDefaultInstance())).isSameAs(PersistedAssetLedger.empty());
        PersistedAssetLedger empty = PersistedAssetLedger.of(
                PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.getDefaultInstance()).build());
        assertThat(empty.invalidReason()).isNull();
        assertThat(empty.classify(GUILD_DEBIT, EPOCH, 1)).isEqualTo(AssetSeqState.UNSEEN);
        assertThat(empty.classify(GUILD_DEBIT, EPOCH, 1025)).isEqualTo(AssetSeqState.JUMP_TOO_FAR);
        assertThat(empty.rejectionReason(GUILD_DEBIT, 1)).isZero();
        assertThat(empty.isPartial(GUILD_DEBIT, 1)).isFalse();
    }

    @Test
    void 合法流号是常量集合() {
        assertThat(AssetLedgerRules.VALID_STREAMS).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(AssetLedgerRules.isValidStream(0)).isFalse();
        assertThat(AssetLedgerRules.isValidStream(6)).isFalse();
        assertThat(AssetLedgerRules.isValidStream(-1)).isFalse();
    }
}
