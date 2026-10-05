package com.game.data.snapshot;

import com.game.player.store.asset.AssetLedgerRules;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.PlayerState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 账本差集（data-ops-spec §4.6.2，D8）：比较快照里的资产通道账本 S 与当前已落盘的账本 C，列出「快照之后在玩家身上已应用」的
 * (流, 纪元, seq)，或判该流「不可证明」。立即可得、不用等沉降；对交易流同样有效。规则全部来自 xm-player-store 的
 * {@link AssetLedgerRules}（与 scene 在线账本、帮会离线读结局同一份判定代码）。
 *
 * <p>判定（逐条流）：
 * <ul>
 *   <li>任一侧账本损坏（{@link AssetLedgerRules#validate} 不通过）→ 整个玩家不可证明，不列行；</li>
 *   <li>S 没有这条流：C 的水位 &gt; 0 → 不可证明（已滑出窗口的 seq 无从得知）；否则 C 窗口里已应用的 seq 全是分歧行；</li>
 *   <li>纪元不同 → 不可证明；</li>
 *   <li>同纪元：C 水位低于 S → 不可证明（当前比快照还旧）；C 水位越过 S 的窗口上沿、或 (S 水位, C 水位] 里有 S 未见的 seq → 不可证明；
 *       C 窗口里已应用、而 S 判为未应用的 seq → 分歧行（S 那里已滑出窗口的同样不可证明）。</li>
 * </ul>
 * C 有、S 也有而 C 没有的流不产生分歧（当前什么都没应用）。分歧行至多 {@link #MAX_ROWS} 条。
 *
 * <p>它是帮会检查的<b>保守超集</b>：快照之后已应用、但调用方还没终结的指令其实可安全回退（会被重投、相对快照恰好一次），也会被列出。
 * 7.2a 只在快照差异里展示（§3.6 第 7 项）；7.2b 作为回档的第二道资产闸。批次 7.2b 起应挪到 xm-player-store 的 asset 包（§7.1）。
 */
public final class LedgerDiff {

    public static final int MAX_ROWS = 1000;

    /** 一条分歧：快照之后已应用的 (流, 纪元, seq)。 */
    public record Row(int stream, long epoch, long seq) {
    }

    /** 一条流不可证明的原因。{@code stream} 为 0 表示整本账本（损坏）。 */
    public record Unprovable(int stream, String reason) {
    }

    /**
     * @param rows          分歧行
     * @param rowsTruncated 分歧行超过上限被截断
     * @param unprovable    不可证明的流
     */
    public record Result(List<Row> rows, boolean rowsTruncated, List<Unprovable> unprovable) {

        /** 没有分歧、没有不可证明。 */
        public boolean clean() {
            return rows.isEmpty() && unprovable.isEmpty();
        }
    }

    private LedgerDiff() {
    }

    public static Result compare(PlayerState snapshot, PlayerState current) {
        AssetOpLedgerState s = snapshot.getAssetLedger();
        AssetOpLedgerState c = current.getAssetLedger();
        List<Unprovable> unprovable = new ArrayList<>();
        String sInvalid = AssetLedgerRules.validate(s);
        String cInvalid = AssetLedgerRules.validate(c);
        if (sInvalid != null || cInvalid != null) {
            if (sInvalid != null) {
                unprovable.add(new Unprovable(0, "快照账本损坏：" + sInvalid));
            }
            if (cInvalid != null) {
                unprovable.add(new Unprovable(0, "当前账本损坏：" + cInvalid));
            }
            return new Result(List.of(), false, unprovable);
        }
        PersistedAssetLedger sLedger = PersistedAssetLedger.restore(s);
        Map<Integer, AssetOpStreamLedgerState> sStreams = byStream(s);
        List<Row> rows = new ArrayList<>();
        boolean[] truncated = {false};
        for (AssetOpStreamLedgerState cs : byStream(c).values()) {
            int stream = cs.getStream();
            AssetOpStreamLedgerState ss = sStreams.get(stream);
            long epoch = cs.getStreamEpoch();
            if (ss == null) {
                if (cs.getWatermark() != 0) {
                    unprovable.add(new Unprovable(stream, "快照里没有这条流，而当前水位 "
                            + Long.toUnsignedString(cs.getWatermark()) + " > 0（已滑出窗口的 seq 无从得知）"));
                    continue;
                }
                forEachApplied(cs, seq -> addRow(rows, truncated, new Row(stream, epoch, seq)));
                continue;
            }
            if (ss.getStreamEpoch() != epoch) {
                unprovable.add(new Unprovable(stream, "纪元不同：快照 " + Long.toUnsignedString(ss.getStreamEpoch())
                        + "，当前 " + Long.toUnsignedString(epoch)));
                continue;
            }
            long sWatermark = ss.getWatermark();
            long cWatermark = cs.getWatermark();
            if (Long.compareUnsigned(cWatermark, sWatermark) < 0) {
                unprovable.add(new Unprovable(stream, "当前水位 " + Long.toUnsignedString(cWatermark) + " 低于快照水位 "
                        + Long.toUnsignedString(sWatermark)));
                continue;
            }
            String gap = gapReason(ss, sWatermark, cWatermark);
            if (gap != null) {
                unprovable.add(new Unprovable(stream, gap));
            }
            boolean[] behind = {false};
            forEachApplied(cs, seq -> {
                AssetSeqState state = sLedger.classify(stream, epoch, seq);
                if (state == AssetSeqState.APPLIED) {
                    return;
                }
                if (state == AssetSeqState.BEHIND_WINDOW) {
                    behind[0] = true;
                    return;
                }
                addRow(rows, truncated, new Row(stream, epoch, seq));
            });
            if (behind[0] && gap == null) {
                unprovable.add(new Unprovable(stream, "当前已应用的 seq 在快照里已滑出窗口"));
            }
        }
        return new Result(rows, truncated[0], unprovable);
    }

    /** (S 水位, C 水位] 里有没有 S 未见的 seq（它们已滑出 C 的窗口，C 的结局无从得知）；有就给出原因。 */
    private static String gapReason(AssetOpStreamLedgerState ss, long sWatermark, long cWatermark) {
        if (cWatermark == sWatermark) {
            return null;
        }
        long gap = cWatermark - sWatermark; // 无符号差，cWatermark > sWatermark 已保证
        if (Long.compareUnsigned(gap, AssetLedgerRules.WINDOW_BITS) > 0) {
            return "当前水位越过快照窗口上沿（差 " + Long.toUnsignedString(gap) + "）";
        }
        for (int i = 0; i < (int) gap; i++) {
            if (!bit(ss.getSeenBits(i >>> 6), i)) {
                return "快照未见的 seq " + Long.toUnsignedString(sWatermark + 1 + i) + " 已滑出当前窗口";
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface SeqConsumer {
        void accept(long seq);
    }

    /** C 窗口里已应用的每个 seq（升序）。窗口已由 validate 保证恰好 16 个字。 */
    private static void forEachApplied(AssetOpStreamLedgerState stream, SeqConsumer consumer) {
        long watermark = stream.getWatermark();
        for (int i = 0; i < AssetLedgerRules.WINDOW_BITS; i++) {
            if (bit(stream.getAppliedBits(i >>> 6), i)) {
                consumer.accept(watermark + 1 + i);
            }
        }
    }

    private static boolean bit(long word, int index) {
        return (word >>> (index & 63) & 1L) != 0;
    }

    private static void addRow(List<Row> rows, boolean[] truncated, Row row) {
        if (rows.size() >= MAX_ROWS) {
            truncated[0] = true;
            return;
        }
        rows.add(row);
    }

    private static Map<Integer, AssetOpStreamLedgerState> byStream(AssetOpLedgerState ledger) {
        Map<Integer, AssetOpStreamLedgerState> out = new TreeMap<>();
        for (AssetOpStreamLedgerState stream : ledger.getStreamsList()) {
            out.put(stream.getStream(), stream);
        }
        return out;
    }
}
