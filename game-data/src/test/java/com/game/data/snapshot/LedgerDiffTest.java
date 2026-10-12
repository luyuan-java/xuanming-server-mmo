package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.data.snapshot.LedgerDiff.Result;
import com.game.data.snapshot.LedgerDiff.Row;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.PlayerState;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** T-L1：账本差集——同纪元逐 seq；纪元变化 / 越过窗口 / 快照缺流 / 账本损坏 → 不可证明；空对空 → 干净。 */
class LedgerDiffTest {

    private static final long EPOCH = 1_700_000_000_000L;

    /** 一条流：watermark 之上按给定 seq 置 seen / applied 位。 */
    private static AssetOpStreamLedgerState stream(int stream, long epoch, long watermark, long[] applied, long[] rejected) {
        long[] seenBits = new long[16];
        long[] appliedBits = new long[16];
        long max = watermark;
        for (long seq : applied) {
            int i = (int) (seq - watermark - 1);
            seenBits[i >>> 6] |= 1L << (i & 63);
            appliedBits[i >>> 6] |= 1L << (i & 63);
            max = Math.max(max, seq);
        }
        for (long seq : rejected) {
            int i = (int) (seq - watermark - 1);
            seenBits[i >>> 6] |= 1L << (i & 63);
            max = Math.max(max, seq);
        }
        AssetOpStreamLedgerState.Builder b = AssetOpStreamLedgerState.newBuilder().setStream(stream).setStreamEpoch(epoch)
                .setWatermark(watermark).setMaxSeq(max);
        for (int w = 0; w < 16; w++) {
            b.addSeenBits(seenBits[w]).addAppliedBits(appliedBits[w]);
        }
        return b.build();
    }

    private static PlayerState state(AssetOpStreamLedgerState... streams) {
        AssetOpLedgerState.Builder ledger = AssetOpLedgerState.newBuilder();
        for (AssetOpStreamLedgerState s : streams) {
            ledger.addStreams(s);
        }
        return PlayerState.newBuilder().setAssetLedger(ledger).build();
    }

    private static long[] seqs(long... v) {
        return v;
    }

    @Test
    void 空对空_干净() {
        Result r = LedgerDiff.compare(PlayerState.getDefaultInstance(), PlayerState.getDefaultInstance());
        assertThat(r.clean()).isTrue();
    }

    @Test
    void 同纪元_当前已应用而快照未应用的seq是分歧行_两边都应用的不算() {
        PlayerState s = state(stream(1, EPOCH, 0, seqs(1, 2), seqs(3)));
        PlayerState c = state(stream(1, EPOCH, 0, seqs(1, 2, 4, 5), seqs(3)));

        Result r = LedgerDiff.compare(s, c);

        assertThat(r.rows()).containsExactly(new Row(1, EPOCH, 4), new Row(1, EPOCH, 5));
        assertThat(r.unprovable()).isEmpty();
        assertThat(r.clean()).isFalse();
    }

    @Test
    void 水位前移但区间里的seq快照都见过_只按窗口列分歧() {
        PlayerState s = state(stream(2, EPOCH, 0, seqs(1, 2, 3), seqs()));
        PlayerState c = state(stream(2, EPOCH, 3, seqs(4), seqs()));

        Result r = LedgerDiff.compare(s, c);

        assertThat(r.rows()).containsExactly(new Row(2, EPOCH, 4));
        assertThat(r.unprovable()).isEmpty();
    }

    @Test
    void 水位越过快照未见的seq_或越过快照窗口上沿_不可证明() {
        PlayerState s = state(stream(1, EPOCH, 0, seqs(1), seqs()));
        PlayerState gap = state(stream(1, EPOCH, 2, seqs(), seqs()));
        assertThat(LedgerDiff.compare(s, gap).unprovable()).singleElement()
                .satisfies(u -> assertThat(u.reason()).contains("滑出当前窗口"));

        PlayerState far = state(stream(1, EPOCH, 2000, seqs(), seqs()));
        assertThat(LedgerDiff.compare(s, far).unprovable()).singleElement()
                .satisfies(u -> assertThat(u.reason()).contains("越过快照窗口上沿"));
    }

    @Test
    void 纪元不同_不可证明() {
        PlayerState s = state(stream(1, EPOCH, 0, seqs(1), seqs()));
        PlayerState c = state(stream(1, EPOCH + 1, 0, seqs(1), seqs()));

        Result r = LedgerDiff.compare(s, c);

        assertThat(r.rows()).isEmpty();
        assertThat(r.unprovable()).singleElement().satisfies(u -> {
            assertThat(u.stream()).isEqualTo(1);
            assertThat(u.reason()).contains("纪元不同");
        });
    }

    @Test
    void 快照没有这条流_当前水位为0时全部已应用都是分歧_水位大于0不可证明() {
        PlayerState s = PlayerState.getDefaultInstance();
        assertThat(LedgerDiff.compare(s, state(stream(3, EPOCH, 0, seqs(1, 7), seqs()))).rows())
                .containsExactly(new Row(3, EPOCH, 1), new Row(3, EPOCH, 7));
        assertThat(LedgerDiff.compare(s, state(stream(3, EPOCH, 10, seqs(11), seqs()))).unprovable()).hasSize(1);
    }

    @Test
    void 当前比快照还旧_快照独有的流不产生分歧() {
        PlayerState s = state(stream(1, EPOCH, 5, seqs(6), seqs()), stream(4, EPOCH, 0, seqs(1), seqs()));
        PlayerState c = state(stream(1, EPOCH, 0, seqs(1), seqs()));

        Result r = LedgerDiff.compare(s, c);

        assertThat(r.unprovable()).singleElement().satisfies(u -> assertThat(u.reason()).contains("低于快照水位"));
        assertThat(r.rows()).isEmpty();
    }

    @Test
    void 任一侧账本损坏_整个玩家不可证明() {
        AssetOpStreamLedgerState broken = stream(1, EPOCH, 0, seqs(1), seqs()).toBuilder().clearSeenBits()
                .addAllSeenBits(Collections.nCopies(3, 0L)).build();
        Result r = LedgerDiff.compare(state(broken), PlayerState.getDefaultInstance());

        assertThat(r.rows()).isEmpty();
        assertThat(r.unprovable()).singleElement().satisfies(u -> {
            assertThat(u.stream()).isZero();
            assertThat(u.reason()).startsWith("快照账本损坏");
        });
        assertThat(LedgerDiff.compare(PlayerState.getDefaultInstance(), state(broken)).unprovable())
                .singleElement().satisfies(u -> assertThat(u.reason()).startsWith("当前账本损坏"));
    }
}
