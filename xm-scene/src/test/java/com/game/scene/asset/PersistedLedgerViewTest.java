package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetStream;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.scene.asset.AssetOpLedger.RecordKind;
import java.util.EnumSet;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * 在线账本（scene 记账）写出的存档，交给只读视图（scene 判 durable、帮会离线读结局，xm-player-store）读回来，结论必须逐 seq 相同
 * （guild-economy-spec §4.9「同一份代码或同一张用例表」：两边共用 AssetLedgerRules，这里再用随机记账序列整体对拍一次，覆盖窗口上滑、
 * 原因环挤出、部分名单挤出、纪元重置）。
 */
class PersistedLedgerViewTest {

    private static final int[] STREAMS = {AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE, AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE};

    @Test
    void 随机记账之后_只读视图与在线账本逐seq结论相同() {
        SplittableRandom random = new SplittableRandom(20261004);
        EnumSet<AssetSeqState> seen = EnumSet.noneOf(AssetSeqState.class);
        for (int round = 0; round < 10; round++) {
            AssetOpLedger live = AssetOpLedger.empty();
            long[] epochs = {100, 100};
            long[] next = {1, 1};
            for (int op = 0; op < 3_000; op++) {
                int s = random.nextInt(STREAMS.length);
                if (random.nextInt(500) == 0) {
                    epochs[s] += 1 + random.nextInt(3);
                    next[s] = 1;
                }
                long seq = next[s] + random.nextInt(3);
                next[s] = seq + 1;
                int pick = random.nextInt(10);
                RecordKind kind = pick < 6 ? RecordKind.APPLIED : pick < 9 ? RecordKind.REJECTED : RecordKind.APPLIED_PARTIAL;
                int reason = kind == RecordKind.REJECTED && random.nextBoolean() ? 27000 + random.nextInt(9) : 0;
                live.record(STREAMS[s], epochs[s], seq, kind, reason);
            }
            PersistedAssetLedger persisted = PersistedAssetLedger.restore(live.toState());
            assertThat(persisted.invalidReason()).isNull();
            for (int s = 0; s < STREAMS.length; s++) {
                int stream = STREAMS[s];
                assertThat(persisted.epochOf(stream)).isEqualTo(live.epochOf(stream));
                for (long epoch = epochs[s] - 1; epoch <= epochs[s] + 1; epoch++) {
                    for (long seq = 0; seq <= next[s] + 1_100; seq++) {
                        AssetSeqState state = live.classify(stream, epoch, seq);
                        seen.add(state);
                        assertThat(persisted.classify(stream, epoch, seq)).as("stream=%d epoch=%d seq=%d", stream, epoch, seq)
                                .isEqualTo(state);
                        if (state == AssetSeqState.APPLIED) {
                            assertThat(persisted.isPartial(stream, seq)).isEqualTo(live.isPartial(stream, seq));
                        }
                        if (state == AssetSeqState.REJECTED) {
                            assertThat(persisted.rejectionReason(stream, seq)).isEqualTo(live.rejectionReason(stream, seq));
                        }
                    }
                }
            }
        }
        assertThat(seen).as("对拍覆盖了全部状态，不是空转").containsExactlyInAnyOrder(AssetSeqState.values());
    }

    @Test
    void 损坏账本_两边都判损坏_原因相同() {
        AssetOpLedger live = AssetOpLedger.empty();
        live.record(STREAMS[0], 100, 1, RecordKind.APPLIED, 0);
        var broken = live.toState().toBuilder();
        broken.getStreamsBuilder(0).setAppliedBits(3, 1);
        AssetOpLedger restored = AssetOpLedger.restore(broken.build());
        PersistedAssetLedger persisted = PersistedAssetLedger.restore(broken.build());
        assertThat(restored.invalidReason()).isNotNull().isEqualTo(persisted.invalidReason());
        assertThat(persisted.classify(STREAMS[0], 100, 1)).isEqualTo(AssetSeqState.INVALID);
    }
}
