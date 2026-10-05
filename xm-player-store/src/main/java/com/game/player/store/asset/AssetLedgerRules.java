package com.game.player.store.asset;

import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import java.util.Set;

/**
 * 通用资产通道幂等账本的<b>只读</b>规则（加载校验与分类），scene 的在线账本（{@code com.game.scene.asset.AssetOpLedger}，
 * 只在场景逻辑线程上读写）与调用方读已落盘账本（{@link PersistedAssetLedger}，帮会离线读结局，guild-economy-spec §4.9 / E8）
 * 共用这一份代码——同基线「C++ 与 Go 两份实现跑同一张用例表」（{@code go/shared/assetop/classify.go:10-14}）的目的，
 * Java 直接做成同一份实现，分叉无从发生。规则同 mmorpg {@code asset_op_ledger.cpp}：
 * <ul>
 *   <li>每条流一个 1024 位窗口，覆盖 (watermark, watermark + 1024]；位 i 对应 seq = watermark + 1 + i，存在第 i/64 个字的第 i%64 位
 *       （与基线的逐位契约，{@code classify.go:131-140}）；</li>
 *   <li>两组位：seen（见过）与 applied（已应用，⊆ seen）；见过未应用 = 拒绝；</li>
 *   <li>同纪元跳号上限 seq ≤ max_seq + 1024（max_seq 接近 uint64 上限时上限不可表示，任何 seq 都不算超，{@code classify.go:105-113}）；</li>
 *   <li>请求纪元更大时按空账本看（真正的整条流重置只在 scene 确定要记账时做）。</li>
 * </ul>
 * 所有 seq / 纪元 / watermark 都是 uint64，放在 long 里按无符号比较。
 *
 * <p>合法流号是常量集合 {@link #VALID_STREAMS}（本模块不依赖 xm-api 的 {@code AssetStream} 枚举，免得调用方为读账本引入通道协议），
 * xm-scene 的测试把它与 {@code AssetStream} 枚举逐项对拍：枚举加了新流而这里没跟上，scene 会把带新流的账本判成损坏（fail-closed，不会误判结局）。
 */
public final class AssetLedgerRules {

    public static final int WINDOW_BITS = 1024;
    public static final int WINDOW_WORDS = WINDOW_BITS / 64;
    public static final int MAX_REJECTIONS = 64;
    public static final int MAX_PARTIAL_SEQS = 64;
    public static final long MAX_SEQ_JUMP = 1024;
    /** UINT64_MAX − 1024：watermark 超过它时 watermark + 1024 会回绕。 */
    public static final long WATERMARK_LIMIT = -1L - WINDOW_BITS;
    /** UINT64_MAX − 1024：max_seq 超过它时跳号上限不可表示，任何 seq 都不算跳号（同基线）。 */
    static final long JUMP_CAP_LIMIT = -1L - MAX_SEQ_JUMP;

    /**
     * 合法流号：1 帮会扣、2 帮会发、3 交易扣、4 交易发、5 系统发（= xm-api {@code AssetStream} 除 UNSPECIFIED 之外的全部取值，
     * 由 xm-scene 的 {@code AssetStreamPinTest} 钉住）。
     */
    public static final Set<Integer> VALID_STREAMS = Set.of(1, 2, 3, 4, 5);

    /** 一条流的窗口视图（scene 的可变流与已落盘的只读流各自实现）。只读，不得在分类过程中被改。 */
    public interface StreamWindow {
        /** 流纪元（≥ 1；空账本没有这条流时调用方传 null，不会走到这里）。 */
        long epoch();

        long watermark();

        long maxSeq();

        /** seen 位图第 word 个字（0 ≤ word < 16）。 */
        long seenWord(int word);

        /** applied 位图第 word 个字（0 ≤ word < 16）。 */
        long appliedWord(int word);
    }

    private AssetLedgerRules() {
    }

    public static boolean isValidStream(int stream) {
        return VALID_STREAMS.contains(stream);
    }

    /**
     * 分类（顺序同基线 {@code ClassifyAssetOpSeq} / {@code ClassifySeq}，不可重排）。
     *
     * @param stream 这条流的窗口；null = 账本里没有这条流（等价于纪元 0 的空账本）。调用方必须先确认账本已通过 {@link #validate}
     */
    public static AssetSeqState classify(StreamWindow stream, long epoch, long seq) {
        if (seq == 0 || epoch == 0) {
            return AssetSeqState.INVALID;
        }
        long ledgerEpoch = stream == null ? 0 : stream.epoch();
        int epochOrder = Long.compareUnsigned(epoch, ledgerEpoch);
        if (epochOrder < 0) {
            return AssetSeqState.STALE_EPOCH;
        }
        if (epochOrder > 0) {
            // 更大的纪元 = 换了一本新流水簿：按空账本（watermark 0、max_seq 0）分类，真正的重置留给 scene 记账时做
            return Long.compareUnsigned(seq, MAX_SEQ_JUMP) <= 0 ? AssetSeqState.UNSEEN : AssetSeqState.JUMP_TOO_FAR;
        }
        // 走到这里纪元相等且非 0，stream 必然非空（空账本的纪元是 0）
        long watermark = stream.watermark();
        if (Long.compareUnsigned(watermark, WATERMARK_LIMIT) > 0) {
            return AssetSeqState.INVALID;
        }
        if (Long.compareUnsigned(seq, watermark) <= 0) {
            return AssetSeqState.BEHIND_WINDOW;
        }
        if (exceedsJumpCap(stream.maxSeq(), seq)) {
            return AssetSeqState.JUMP_TOO_FAR;
        }
        if (Long.compareUnsigned(seq, watermark + WINDOW_BITS) > 0) {
            return AssetSeqState.AHEAD_OF_WINDOW;
        }
        int index = (int) (seq - watermark - 1);
        if (!testBit(stream.seenWord(index >>> 6), index)) {
            return AssetSeqState.UNSEEN;
        }
        return testBit(stream.appliedWord(index >>> 6), index) ? AssetSeqState.APPLIED : AssetSeqState.REJECTED;
    }

    /** max_seq 接近 uint64 上限时上限不可表示，任何 seq 都不算超（同基线 ExceedsJumpCap 与 Go classify 的约定）。 */
    static boolean exceedsJumpCap(long maxSeq, long seq) {
        if (Long.compareUnsigned(maxSeq, JUMP_CAP_LIMIT) > 0) {
            return false;
        }
        return Long.compareUnsigned(seq, maxSeq + MAX_SEQ_JUMP) > 0;
    }

    /** 位 index 在它所在的那个字里是否为 1（index 取模 64）。 */
    private static boolean testBit(long word, int index) {
        return (word >>> (index & 63) & 1L) != 0;
    }

    private static boolean testBit(long[] words, int index) {
        return testBit(words[index >>> 6], index);
    }

    // ------------------------------------------------------------------ 加载校验

    /** 同基线 ValidateAssetOpLedger；返回 null = 通过，否则是可进日志的损坏原因。 */
    public static String validate(AssetOpLedgerState state) {
        long previousStream = -1;
        for (int i = 0; i < state.getStreamsCount(); i++) {
            AssetOpStreamLedgerState ledger = state.getStreams(i);
            long stream = Integer.toUnsignedLong(ledger.getStream());
            if (stream <= previousStream) {
                return prefix(ledger, i) + "流未严格升序（重复或乱序）";
            }
            previousStream = stream;
            String reason = validateStream(ledger, i);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String validateStream(AssetOpStreamLedgerState ledger, int index) {
        String prefix = prefix(ledger, index);
        if (!isValidStream(ledger.getStream())) {
            return prefix + "不是合法流";
        }
        if (ledger.getStreamEpoch() == 0) {
            return prefix + "stream_epoch = 0";
        }
        if (ledger.getSeenBitsCount() != WINDOW_WORDS) {
            return prefix + "seen_bits 数量=" + ledger.getSeenBitsCount() + " 应为 16";
        }
        if (ledger.getAppliedBitsCount() != WINDOW_WORDS) {
            return prefix + "applied_bits 数量=" + ledger.getAppliedBitsCount() + " 应为 16";
        }
        long[] seen = new long[WINDOW_WORDS];
        long[] applied = new long[WINDOW_WORDS];
        for (int word = 0; word < WINDOW_WORDS; word++) {
            seen[word] = ledger.getSeenBits(word);
            applied[word] = ledger.getAppliedBits(word);
            if ((applied[word] & ~seen[word]) != 0) {
                return prefix + "applied 不是 seen 的子集，word=" + word;
            }
        }
        long watermark = ledger.getWatermark();
        if (Long.compareUnsigned(watermark, WATERMARK_LIMIT) > 0) {
            return prefix + "watermark 接近溢出";
        }
        if (Long.compareUnsigned(ledger.getMaxSeq(), watermark) < 0) {
            return prefix + "max_seq=" + Long.toUnsignedString(ledger.getMaxSeq()) + " < watermark="
                    + Long.toUnsignedString(watermark);
        }
        if (ledger.getRejectionsCount() > MAX_REJECTIONS) {
            return prefix + "rejections 数量=" + ledger.getRejectionsCount() + " 超过 64";
        }
        long previous = 0;
        for (int i = 0; i < ledger.getRejectionsCount(); i++) {
            long seq = ledger.getRejections(i).getSeq();
            if (i > 0 && Long.compareUnsigned(seq, previous) <= 0) {
                return prefix + "rejections 未严格升序，seq=" + Long.toUnsignedString(seq);
            }
            previous = seq;
            if (!insideWindow(watermark, seq)) {
                return prefix + "rejections 越窗，seq=" + Long.toUnsignedString(seq);
            }
            int bit = (int) (seq - watermark - 1);
            if (!testBit(seen, bit) || testBit(applied, bit)) {
                return prefix + "rejections 对应位不是「已见且未应用」，seq=" + Long.toUnsignedString(seq);
            }
        }
        if (ledger.getPartialSeqsCount() > MAX_PARTIAL_SEQS) {
            return prefix + "partial_seqs 数量=" + ledger.getPartialSeqsCount() + " 超过 64";
        }
        previous = 0;
        for (int i = 0; i < ledger.getPartialSeqsCount(); i++) {
            long seq = ledger.getPartialSeqs(i);
            if (i > 0 && Long.compareUnsigned(seq, previous) <= 0) {
                return prefix + "partial_seqs 未严格升序，seq=" + Long.toUnsignedString(seq);
            }
            previous = seq;
            if (!insideWindow(watermark, seq)) {
                return prefix + "partial_seqs 越窗，seq=" + Long.toUnsignedString(seq);
            }
            if (!testBit(applied, (int) (seq - watermark - 1))) {
                return prefix + "partial_seqs 对应位不是 applied，seq=" + Long.toUnsignedString(seq);
            }
        }
        return null;
    }

    /** seq ∈ (watermark, watermark + 1024]（调用前已确认 watermark 不会溢出）。 */
    private static boolean insideWindow(long watermark, long seq) {
        return Long.compareUnsigned(seq, watermark) > 0 && Long.compareUnsigned(seq, watermark + WINDOW_BITS) <= 0;
    }

    private static String prefix(AssetOpStreamLedgerState ledger, int index) {
        return "streams[" + index + "] stream=" + Integer.toUnsignedString(ledger.getStream()) + " ";
    }
}
