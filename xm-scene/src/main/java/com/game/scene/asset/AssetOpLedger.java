package com.game.scene.asset;

import com.game.api.proto.AssetStream;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpRejectionState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 一个玩家的通用资产通道幂等账本（只在场景逻辑线程上读写），规则同 mmorpg {@code asset_op_ledger.cpp}：
 * <ul>
 *   <li>每条流一个 1024 位窗口，覆盖 (watermark, watermark + 1024]；位 i 对应 seq = watermark + 1 + i，存在第 i/64 个字的第 i%64 位。
 *       窗口只跟着「本纪元见过的最大 seq」上滑，不做连续前缀压缩（压缩会把调用方仍未决、这边已记账的 seq 挤出窗口）；</li>
 *   <li>两组位：seen（见过）与 applied（已应用，⊆ seen）；见过未应用 = 拒绝；</li>
 *   <li>拒绝原因环至多 64 条、只收非 0 原因（中止占位不占名额），挤掉最小的；被挤掉的原因退化成 0，账务不受影响（结局只看位图）；</li>
 *   <li>部分发放名单至多 64 条，挤掉最小的；</li>
 *   <li>同纪元跳号上限：seq ≤ max_seq + 1024；请求纪元更大时按空账本看，真正的整条流重置只在确定要记账时做；</li>
 *   <li>加载时校验自洽（{@link #restore}）；损坏不改写、原样带回，调用方据 {@link #invalidReason()} 关闭该玩家的资产通道（fail-closed）。</li>
 * </ul>
 * 所有 seq / 纪元 / watermark 都是 uint64，放在 long 里按无符号比较。流号是存档里的 uint32，合法值跟着 {@link AssetStream} 枚举走。
 */
public final class AssetOpLedger {

    public static final int WINDOW_BITS = 1024;
    static final int WINDOW_WORDS = WINDOW_BITS / 64;
    public static final int MAX_REJECTIONS = 64;
    public static final int MAX_PARTIAL_SEQS = 64;
    public static final long MAX_SEQ_JUMP = 1024;
    /** UINT64_MAX − 1024：watermark 超过它时 watermark + 1024 会回绕。 */
    private static final long WATERMARK_LIMIT = -1L - WINDOW_BITS;
    /** UINT64_MAX − 1024：max_seq 超过它时跳号上限不可表示，任何 seq 都不算跳号（同基线）。 */
    private static final long JUMP_CAP_LIMIT = -1L - MAX_SEQ_JUMP;

    /** 某个 (纪元, seq) 在账本里的状态（同基线 AssetOpSeqState）。 */
    public enum SeqState {
        /** seq = 0、纪元 = 0，或 watermark 已接近溢出。 */
        INVALID,
        /** 请求纪元 < 账本纪元：旧流水簿上的号，结局不可采信。 */
        STALE_EPOCH,
        /** seq ≤ watermark：已滑出窗口，结局不可知。 */
        BEHIND_WINDOW,
        /** 窗口内未见（含「请求纪元更大、按空账本看」）。 */
        UNSEEN,
        /** seq > watermark + 1024 但没超跳号上限：必为未见，记账时窗口上滑。 */
        AHEAD_OF_WINDOW,
        /** seq > max_seq + 1024（纪元更大时为 seq > 1024）。 */
        JUMP_TOO_FAR,
        APPLIED,
        REJECTED
    }

    /** 一次结局写什么。APPLIED_PARTIAL 在位图上同 APPLIED，另把 seq 记进部分发放名单。 */
    public enum RecordKind { APPLIED, APPLIED_PARTIAL, REJECTED }

    /** 流号 → 单条流的账本，按流号升序（存档顺序稳定，周期存盘的按值比对不会因顺序抖动误判为变更）。 */
    private final TreeMap<Integer, Stream> streams;
    private final UnknownFieldSet unknownFields;
    /** 加载时校验不过的原样存档；null = 自洽。 */
    private final AssetOpLedgerState corrupted;
    private final String invalidReason;
    /** 运行时（不持久化、随实例清空）：上一次为确认 durable 请求存盘的毫秒，给已见未 durable 的重查限频。 */
    private boolean persistRequested;
    private long lastPersistRequestMs;

    private AssetOpLedger(TreeMap<Integer, Stream> streams, UnknownFieldSet unknownFields, AssetOpLedgerState corrupted,
                          String invalidReason) {
        this.streams = streams;
        this.unknownFields = unknownFields;
        this.corrupted = corrupted;
        this.invalidReason = invalidReason;
    }

    /** 新号 / 从没记过账：空账本。 */
    public static AssetOpLedger empty() {
        return new AssetOpLedger(new TreeMap<>(Integer::compareUnsigned), UnknownFieldSet.getDefaultInstance(), null, null);
    }

    /** 从存档恢复；不自洽时返回一本「损坏」账本（{@link #invalidReason()} 非 null），写回时原样带回存档、不改写。 */
    public static AssetOpLedger restore(AssetOpLedgerState state) {
        String reason = validate(state);
        if (reason != null) {
            return new AssetOpLedger(new TreeMap<>(Integer::compareUnsigned), UnknownFieldSet.getDefaultInstance(), state,
                    reason);
        }
        TreeMap<Integer, Stream> streams = new TreeMap<>(Integer::compareUnsigned);
        for (AssetOpStreamLedgerState stored : state.getStreamsList()) {
            streams.put(stored.getStream(), Stream.restore(stored));
        }
        return new AssetOpLedger(streams, state.getUnknownFields(), null, null);
    }

    /** 加载时判定的损坏原因（可直接进日志）；null = 自洽。 */
    public String invalidReason() {
        return invalidReason;
    }

    /** 距上一次为确认 durable 请求存盘还不到 intervalMs（没请求过为 false）。 */
    boolean persistRequestedWithin(long nowMs, long intervalMs) {
        return persistRequested && nowMs - lastPersistRequestMs < intervalMs;
    }

    void markPersistRequested(long nowMs) {
        persistRequested = true;
        lastPersistRequestMs = nowMs;
    }

    /** 没有任何流、没有不认识的字段、也不是损坏账本：持久化时可省略整段。 */
    public boolean isPristine() {
        return corrupted == null && streams.isEmpty() && unknownFields.asMap().isEmpty();
    }

    public AssetOpLedgerState toState() {
        if (corrupted != null) {
            return corrupted;
        }
        AssetOpLedgerState.Builder state = AssetOpLedgerState.newBuilder().setUnknownFields(unknownFields);
        for (Stream stream : streams.values()) {
            state.addStreams(stream.toState());
        }
        return state.build();
    }

    /** 分类（顺序同基线 ClassifyAssetOpSeq）。损坏账本恒为 INVALID（调用方应先判 {@link #invalidReason()}）。 */
    public SeqState classify(int stream, long epoch, long seq) {
        if (corrupted != null) {
            return SeqState.INVALID;
        }
        return classify(streams.get(stream), epoch, seq);
    }

    /** 该流当前纪元（没有这条流为 0），日志用。 */
    public long epochOf(int stream) {
        Stream s = streams.get(stream);
        return s == null ? 0 : s.epoch;
    }

    /** 该流 watermark（没有这条流为 0），日志用。 */
    public long watermarkOf(int stream) {
        Stream s = streams.get(stream);
        return s == null ? 0 : s.watermark;
    }

    /** 该流本纪元见过的最大 seq（没有这条流为 0），日志用。 */
    public long maxSeqOf(int stream) {
        Stream s = streams.get(stream);
        return s == null ? 0 : s.maxSeq;
    }

    /** 窗口内某个被拒 seq 的原因；查不到（不在环里 / 被挤掉 / 中止占位 / 不是拒绝）回 0。 */
    public int rejectionReason(int stream, long seq) {
        Stream s = streams.get(stream);
        if (s == null) {
            return 0;
        }
        AssetOpRejectionState rejection = s.rejections.get(seq);
        return rejection == null ? 0 : rejection.getReasonTipId();
    }

    /** seq 是否在部分发放名单里（前提是它已判为 APPLIED）。 */
    public boolean isPartial(int stream, long seq) {
        Stream s = streams.get(stream);
        return s != null && s.partials.contains(seq);
    }

    /**
     * 记一次结局（同基线 RecordAssetOpOutcome）。前置：{@link #classify} ∈ {UNSEEN, AHEAD_OF_WINDOW}；不满足返回 false 且什么都不改
     * （调用方按 UNKNOWN 处理并告警）。先对原账本判前置、再决定是否建流 / 重置：前置失败时不会留下纪元 0 的空流，已见结局也不会凭空消失。
     *
     * @param reasonTipId 只对 REJECTED 有意义；0 = 中止占位（不进原因环）
     */
    public boolean record(int stream, long epoch, long seq, RecordKind kind, int reasonTipId) {
        if (corrupted != null) {
            return false;
        }
        Stream s = streams.get(stream);
        SeqState state = classify(s, epoch, seq);
        if (state != SeqState.UNSEEN && state != SeqState.AHEAD_OF_WINDOW) {
            return false;
        }
        if (s == null) {
            s = new Stream(stream, UnknownFieldSet.getDefaultInstance());
            streams.put(stream, s);
        }
        if (Long.compareUnsigned(epoch, s.epoch) > 0) {
            s.reset(epoch);
        }
        // classify 已排除 watermark 接近溢出的账本，watermark + 1024 不会回绕
        long watermark = s.watermark;
        if (Long.compareUnsigned(seq, watermark + WINDOW_BITS) > 0) {
            long shift = seq - (watermark + WINDOW_BITS);
            if (Long.compareUnsigned(shift, WINDOW_BITS) >= 0) {
                Arrays.fill(s.seen, 0);
                Arrays.fill(s.applied, 0);
            } else {
                shiftRight(s.seen, (int) shift);
                shiftRight(s.applied, (int) shift);
            }
            watermark += shift;
            s.watermark = watermark;
            s.pruneAtOrBelow(watermark);
        }
        int index = (int) (seq - watermark - 1);
        setBit(s.seen, index);
        if (kind != RecordKind.REJECTED) {
            setBit(s.applied, index);
        }
        if (kind == RecordKind.APPLIED_PARTIAL) {
            s.partials.add(seq);
            // 超过上限丢最小的一条（基线 §4.33）：之后重查答 partial = false；首次答复一定带 partial = true，人工补偿以当时的 ERROR 日志为准
            while (s.partials.size() > MAX_PARTIAL_SEQS) {
                s.partials.pollFirst();
            }
        }
        if (kind == RecordKind.REJECTED && reasonTipId != 0) {
            s.rejections.put(seq, AssetOpRejectionState.newBuilder().setSeq(seq).setReasonTipId(reasonTipId).build());
            // 超过上限丢最小的一条：原因只用于展示，丢掉不影响 APPLIED / REJECTED 的判定
            while (s.rejections.size() > MAX_REJECTIONS) {
                s.rejections.pollFirstEntry();
            }
        }
        if (Long.compareUnsigned(seq, s.maxSeq) > 0) {
            s.maxSeq = seq;
        }
        return true;
    }

    // ------------------------------------------------------------------ 分类与位图

    static SeqState classify(Stream s, long epoch, long seq) {
        if (seq == 0 || epoch == 0) {
            return SeqState.INVALID;
        }
        long ledgerEpoch = s == null ? 0 : s.epoch;
        int epochOrder = Long.compareUnsigned(epoch, ledgerEpoch);
        if (epochOrder < 0) {
            return SeqState.STALE_EPOCH;
        }
        if (epochOrder > 0) {
            // 更大的纪元 = 换了一本新流水簿：按空账本（watermark 0、max_seq 0）分类，真正的重置留到 record 里做
            return Long.compareUnsigned(seq, MAX_SEQ_JUMP) <= 0 ? SeqState.UNSEEN : SeqState.JUMP_TOO_FAR;
        }
        // 走到这里纪元相等且非 0，s 必然非空（空账本的纪元是 0）
        long watermark = s.watermark;
        if (Long.compareUnsigned(watermark, WATERMARK_LIMIT) > 0) {
            return SeqState.INVALID;
        }
        if (Long.compareUnsigned(seq, watermark) <= 0) {
            return SeqState.BEHIND_WINDOW;
        }
        if (exceedsJumpCap(s.maxSeq, seq)) {
            return SeqState.JUMP_TOO_FAR;
        }
        if (Long.compareUnsigned(seq, watermark + WINDOW_BITS) > 0) {
            return SeqState.AHEAD_OF_WINDOW;
        }
        int index = (int) (seq - watermark - 1);
        if (!testBit(s.seen, index)) {
            return SeqState.UNSEEN;
        }
        return testBit(s.applied, index) ? SeqState.APPLIED : SeqState.REJECTED;
    }

    /** max_seq 接近 uint64 上限时上限不可表示，任何 seq 都不算超（同基线 ExceedsJumpCap 与 Go classify 的约定）。 */
    private static boolean exceedsJumpCap(long maxSeq, long seq) {
        if (Long.compareUnsigned(maxSeq, JUMP_CAP_LIMIT) > 0) {
            return false;
        }
        return Long.compareUnsigned(seq, maxSeq + MAX_SEQ_JUMP) > 0;
    }

    private static boolean testBit(long[] words, int index) {
        return (words[index >>> 6] >>> (index & 63) & 1L) != 0;
    }

    private static void setBit(long[] words, int index) {
        words[index >>> 6] |= 1L << (index & 63);
    }

    /** 1024 位大整数整体右移 shift 位（低位丢弃）：新的位 i = 旧的位 i + shift。前置 0 < shift < 1024。 */
    static void shiftRight(long[] words, int shift) {
        int wordShift = shift >>> 6;
        int bitShift = shift & 63;
        long[] shifted = new long[WINDOW_WORDS];
        for (int word = 0; word < WINDOW_WORDS; word++) {
            int src = word + wordShift;
            long value = src < WINDOW_WORDS ? words[src] >>> bitShift : 0;
            if (bitShift != 0 && src + 1 < WINDOW_WORDS) {
                value |= words[src + 1] << (64 - bitShift);
            }
            shifted[word] = value;
        }
        System.arraycopy(shifted, 0, words, 0, WINDOW_WORDS);
    }

    // ------------------------------------------------------------------ 加载校验

    /** 合法流 = {@link AssetStream} 当前枚举里除 UNSPECIFIED 之外的任意一个（跟着枚举走，不写死上界）。 */
    static boolean isValidStream(int stream) {
        AssetStream known = AssetStream.forNumber(stream);
        return known != null && known != AssetStream.ASSET_STREAM_UNSPECIFIED;
    }

    /** 同基线 ValidateAssetOpLedger；返回 null = 通过，否则是可进日志的损坏原因。 */
    static String validate(AssetOpLedgerState state) {
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

    // ------------------------------------------------------------------ 单条流

    /** 单条流的账本（只在 {@link AssetOpLedger} 内部改）。 */
    static final class Stream {
        final int stream;
        long epoch;
        long watermark;
        long maxSeq;
        final long[] seen = new long[WINDOW_WORDS];
        final long[] applied = new long[WINDOW_WORDS];
        /** seq → 拒绝条目（原样保留存档里的条目，含不认识的字段），按 seq 无符号升序。 */
        final TreeMap<Long, AssetOpRejectionState> rejections = new TreeMap<>(Long::compareUnsigned);
        /** 部分发放的 seq，按无符号升序。 */
        final TreeSet<Long> partials = new TreeSet<>(Long::compareUnsigned);
        /** 存档里本版本不认识的字段：原样带回。 */
        private final UnknownFieldSet unknownFields;

        Stream(int stream, UnknownFieldSet unknownFields) {
            this.stream = stream;
            this.unknownFields = unknownFields;
        }

        /** 前置：已通过 {@link #validate}。 */
        static Stream restore(AssetOpStreamLedgerState stored) {
            Stream s = new Stream(stored.getStream(), stored.getUnknownFields());
            s.epoch = stored.getStreamEpoch();
            s.watermark = stored.getWatermark();
            s.maxSeq = stored.getMaxSeq();
            for (int word = 0; word < WINDOW_WORDS; word++) {
                s.seen[word] = stored.getSeenBits(word);
                s.applied[word] = stored.getAppliedBits(word);
            }
            for (AssetOpRejectionState rejection : stored.getRejectionsList()) {
                // 存档里的 0 原因（基线允许）照收：查它回 0，与没有这条一样
                s.rejections.put(rejection.getSeq(), rejection);
            }
            s.partials.addAll(stored.getPartialSeqsList());
            return s;
        }

        /** 整条流重置到新纪元（只由 record 在确定要记账时调用；闸门回 RETRY 时不得重置）。 */
        void reset(long newEpoch) {
            epoch = newEpoch;
            watermark = 0;
            maxSeq = 0;
            Arrays.fill(seen, 0);
            Arrays.fill(applied, 0);
            rejections.clear();
            partials.clear();
        }

        /** 窗口上滑之后，seq ≤ 新 watermark 的原因 / 部分发放条目已不可查询，删掉。 */
        void pruneAtOrBelow(long newWatermark) {
            rejections.headMap(newWatermark, true).clear();
            partials.headSet(newWatermark, true).clear();
        }

        AssetOpStreamLedgerState toState() {
            AssetOpStreamLedgerState.Builder state = AssetOpStreamLedgerState.newBuilder()
                    .setStream(stream)
                    .setWatermark(watermark)
                    .setMaxSeq(maxSeq)
                    .setStreamEpoch(epoch)
                    .setUnknownFields(unknownFields);
            for (int word = 0; word < WINDOW_WORDS; word++) {
                state.addSeenBits(seen[word]);
                state.addAppliedBits(applied[word]);
            }
            state.addAllRejections(rejections.values());
            state.addAllPartialSeqs(partials);
            return state.build();
        }
    }
}
