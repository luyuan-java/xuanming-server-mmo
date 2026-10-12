package com.game.player.store.asset;

import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpRejectionState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.PlayerState;
import java.util.HashMap;
import java.util.Map;

/**
 * 已落盘账本（{@code player_state.asset_ledger}）的<b>只读</b>视图：scene 判 durable（看最近一次确认落库的快照里有没有这条结局）
 * 与调用方离线读结局（帮会重投循环，guild-economy-spec §2.10 / §4.9 / E8）都用它；规则全部来自 {@link AssetLedgerRules}，
 * 与 scene 在线账本是同一份判定代码。
 *
 * <p>加载即校验：不自洽的账本一律按损坏处理（{@link #invalidReason()} 非 null、分类恒 {@link AssetSeqState#INVALID}），
 * 绝不把坏账本当空账本——那会把已应用的 seq 看成未见。基线 Go 的 {@code ClassifyPersisted} 不做整本校验（{@code classify.go:143-161}），
 * Java 与 scene 同口径更严：损坏只会让调用方「继续等」，不会得出结论。
 *
 * <p>不可变，线程安全。
 */
public final class PersistedAssetLedger {

    private static final PersistedAssetLedger EMPTY = new PersistedAssetLedger(Map.of(), null);

    /** 流号 → 这条流（已通过校验：恰好 16 个字、原因与部分名单升序且在窗内）。 */
    private final Map<Integer, AssetOpStreamLedgerState> streams;
    private final String invalidReason;

    private PersistedAssetLedger(Map<Integer, AssetOpStreamLedgerState> streams, String invalidReason) {
        this.streams = streams;
        this.invalidReason = invalidReason;
    }

    /** 没有任何流的账本（从没收到过资产通道请求）。 */
    public static PersistedAssetLedger empty() {
        return EMPTY;
    }

    /** 从存档恢复并校验；不自洽时返回一本「损坏」账本。 */
    public static PersistedAssetLedger restore(AssetOpLedgerState state) {
        String reason = AssetLedgerRules.validate(state);
        if (reason != null) {
            return new PersistedAssetLedger(Map.of(), reason);
        }
        if (state.getStreamsCount() == 0) {
            return EMPTY;
        }
        Map<Integer, AssetOpStreamLedgerState> byStream = new HashMap<>();
        for (AssetOpStreamLedgerState stream : state.getStreamsList()) {
            byStream.put(stream.getStream(), stream);
        }
        return new PersistedAssetLedger(Map.copyOf(byStream), null);
    }

    /** 一份玩家状态里的账本（没有这段 = 空账本）。 */
    public static PersistedAssetLedger of(PlayerState state) {
        return state.hasAssetLedger() ? restore(state.getAssetLedger()) : EMPTY;
    }

    /** 加载时判定的损坏原因（可直接进日志）；null = 自洽。 */
    public String invalidReason() {
        return invalidReason;
    }

    /** 分类（同 {@link AssetLedgerRules#classify}）；损坏账本恒为 {@link AssetSeqState#INVALID}。 */
    public AssetSeqState classify(int stream, long epoch, long seq) {
        if (invalidReason != null) {
            return AssetSeqState.INVALID;
        }
        AssetOpStreamLedgerState s = streams.get(stream);
        return AssetLedgerRules.classify(s == null ? null : new Window(s), epoch, seq);
    }

    /** 该流当前纪元（没有这条流或账本损坏为 0）。 */
    public long epochOf(int stream) {
        AssetOpStreamLedgerState s = streams.get(stream);
        return s == null ? 0 : s.getStreamEpoch();
    }

    /** 窗口内某个被拒 seq 的原因；查不到（不在环里 / 被挤掉 / 中止占位 / 不是拒绝）回 0。 */
    public int rejectionReason(int stream, long seq) {
        AssetOpStreamLedgerState s = streams.get(stream);
        if (s == null) {
            return 0;
        }
        for (AssetOpRejectionState rejection : s.getRejectionsList()) {
            if (rejection.getSeq() == seq) {
                return rejection.getReasonTipId();
            }
        }
        return 0;
    }

    /** seq 是否在部分发放名单里（前提是它已判为 APPLIED）。 */
    public boolean isPartial(int stream, long seq) {
        AssetOpStreamLedgerState s = streams.get(stream);
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.getPartialSeqsCount(); i++) {
            if (s.getPartialSeqs(i) == seq) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某个 seq 已落盘的结局（同基线 {@code ClassifyPersisted}，{@code classify.go:143-161}）：只有 APPLIED / REJECTED 是结论
     * （{@link Outcome#conclusive()}），可直接当 durable——读的是已提交、带归属围栏的那份记录，按不变量 I2 已记账的结局永不改变；
     * 其余一律不是结论，调用方继续等，<b>绝不</b>据「未见」判中止（不变量 I7）。
     */
    public Outcome outcomeOf(int stream, long epoch, long seq) {
        AssetSeqState state = classify(stream, epoch, seq);
        return switch (state) {
            case APPLIED -> new Outcome(state, isPartial(stream, seq), 0);
            case REJECTED -> new Outcome(state, false, rejectionReason(stream, seq));
            default -> new Outcome(state, false, 0);
        };
    }

    /**
     * 已落盘结局。
     *
     * @param state           分类结果
     * @param partial         APPLIED 且在部分发放名单里（调用方据此终结为 APPLIED_PARTIAL，原因记 27007）
     * @param rejectionReason REJECTED 的原因（中止占位 / 原因被挤掉为 0）；其余为 0
     */
    public record Outcome(AssetSeqState state, boolean partial, int rejectionReason) {

        /** APPLIED / REJECTED 才是结论。 */
        public boolean conclusive() {
            return state == AssetSeqState.APPLIED || state == AssetSeqState.REJECTED;
        }
    }

    /** 已校验的存档流的窗口视图（16 个字由校验保证）。 */
    private record Window(AssetOpStreamLedgerState s) implements AssetLedgerRules.StreamWindow {
        @Override
        public long epoch() {
            return s.getStreamEpoch();
        }

        @Override
        public long watermark() {
            return s.getWatermark();
        }

        @Override
        public long maxSeq() {
            return s.getMaxSeq();
        }

        @Override
        public long seenWord(int word) {
            return s.getSeenBits(word);
        }

        @Override
        public long appliedWord(int word) {
            return s.getAppliedBits(word);
        }
    }
}
