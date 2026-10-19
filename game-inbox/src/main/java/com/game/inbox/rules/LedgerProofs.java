package com.game.inbox.rules;

import com.game.player.store.inbox.InboxLedgerRules;
import com.game.player.store.state.InboxDecisionState;
import com.game.player.store.state.InboxDomainCursorState;
import com.game.player.store.state.InboxLedgerState;
import com.game.proto.player_inbox.PlayerInboxFinalizer;
import com.game.proto.player_inbox.PlayerInboxForceVoidVerdict;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxReconcileDiffKind;
import com.game.proto.player_inbox.player_inbox_item_row;
import java.util.List;

/**
 * 拿已落盘的消费账本（{@code player_state.inbox_ledger}）当证据的纯判定：VoidItem 强制模式的「从未执行」证明（设计 §7.9，PARITY 第 ① 条）
 * 与两个对账入口共用的环内空洞谓词（设计 §9.3，PARITY 第 ⑥ 条）。规则同 mmorpg {@code decide.go} 的 {@code proveNeverExecuted} /
 * {@code finalizedLedgerGap} / {@code reconcileDiff}。账本结构校验用 game-player-store 的 {@link InboxLedgerRules#validate}（证据口径另查
 * request_id ≠ 0）。所有 seq / request_id 都是 uint64，放在 long 里按无符号比较。线程安全（纯函数）。
 */
public final class LedgerProofs {

    private LedgerProofs() {
    }

    // ================================================================ 强制作废的证明

    /**
     * 强制作废的证明结论，加两个只用于日志留证的读数。
     *
     * @param decidedThrough 账本该域的水位（账本缺失、不合法或没有该域时为 0）
     * @param ringRequestId  环里同 seq 那一条记的 request_id（没有这一条时为 0）
     */
    public record ForceVoidProof(PlayerInboxForceVoidVerdict verdict, long decidedThrough, long ringRequestId) {

        static ForceVoidProof of(PlayerInboxForceVoidVerdict verdict) {
            return new ForceVoidProof(verdict, 0, 0);
        }
    }

    /**
     * 用已落盘账本证明一条已投递、仍 PENDING 的行在这一消费方谱系上<b>从未执行</b>。四项全部成立才放行（PROVEN）：
     * <ol>
     *   <li>行已投递（delivery_count &gt; 0）——否则 NOT_DELIVERED（改用普通模式直接作废）；</li>
     *   <li>账本读到了（否则 LEDGER_ABSENT）且结构合法（{@link InboxLedgerRules#validate}，另加 request_id ≠ 0；否则 LEDGER_INVALID：
     *       那种账本在 scene 侧整份判坏、根本走不到回放核对，更不能拿来当证据）；</li>
     *   <li>该域 decided_through ≥ seq 且环里同 seq 记的是<b>另一个</b> request_id——水位没到判 NOT_DECIDED，环里没有该 seq 判 SEQ_NOT_IN_RING
     *       （原结局已挤出环，可能已执行）；</li>
     *   <li>环里任何位置都没有本行的 request_id——否则 REQUEST_IN_LEDGER（同 seq = 回放或只有类型不同；别的 seq = 同一处置曾以别的 seq
     *       执行过，例如收件箱库恢复后按原键重下）。只查水位与同 seq 会把这种行判成 PROVEN，运维按新 request_id 重下后处置会执行两次。</li>
     * </ol>
     * 为什么这样就够：消费方拒绝给 seq ≤ 水位的条目记账，对这一行只会判 MISMATCH 或 UNKNOWN，不会执行；账本只升、环里的条目只会被挤出，
     * 先读账本、后在锁内判定没有竞态。调用方已确认行仍 PENDING。
     *
     * @param ledger 已落盘账本；null = 读不到存档
     */
    public static ForceVoidProof proveNeverExecuted(player_inbox_item_row row, InboxLedgerState ledger) {
        if (row.getDeliveryCount() == 0) {
            return ForceVoidProof.of(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_NOT_DELIVERED);
        }
        if (ledger == null) {
            return ForceVoidProof.of(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_LEDGER_ABSENT);
        }
        if (InboxLedgerRules.validate(ledger, true) != null) {
            return ForceVoidProof.of(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_LEDGER_INVALID);
        }
        InboxDomainCursorState cursor = InboxLedgerRules.findCursor(ledger, row.getDomain());
        if (cursor == null) {
            return ForceVoidProof.of(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_NOT_DECIDED);
        }
        long decidedThrough = cursor.getDecidedThroughSeq();
        if (Long.compareUnsigned(decidedThrough, row.getSeq()) < 0) {
            return new ForceVoidProof(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_NOT_DECIDED, decidedThrough, 0);
        }
        boolean seqDecided = false;
        long ringRequestId = 0;
        for (InboxDecisionState decision : cursor.getRecentList()) {
            if (decision.getSeq() == row.getSeq()) {
                seqDecided = true;
                ringRequestId = decision.getRequestId();
            }
            if (decision.getRequestId() == row.getRequestId()) {
                return new ForceVoidProof(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_REQUEST_IN_LEDGER, decidedThrough,
                        ringRequestId);
            }
        }
        if (!seqDecided) {
            return new ForceVoidProof(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_SEQ_NOT_IN_RING, decidedThrough, 0);
        }
        return new ForceVoidProof(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_PROVEN, decidedThrough, ringRequestId);
    }

    // ================================================================ 对账：环内空洞谓词

    /** 一条由消费方终结的行在已落盘账本里的缺口种类（只进日志；对外的 diff kind 一律 FINAL_NOT_IN_LEDGER）。 */
    public enum LedgerGap {
        /** 账本里有这一行的结局，或无从核对（已挤出满环、SETTLED_UNKNOWN、不是消费方终结的行）。 */
        NONE("none"),
        /** 账本水位没到这个 seq（回档、存档丢数据）。 */
        BEYOND_WATERMARK("beyond_watermark"),
        /** 水位已过、seq 落在环该覆盖的范围内，环里却查无此 seq：丢掉的结局被之后的条目掩盖（评审 M6）。 */
        MISSING_IN_RING("missing_in_ring"),
        /** 环里同 seq 记的是另一条（request_id / msg_type 不同）或另一个结局。 */
        RING_MISMATCH("ring_mismatch");

        private final String label;

        LedgerGap(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 对账要核对的一条终态行（谓词用到的列，外加翻页游标要的 updated_ms）。
     */
    public record FinalizedRef(long playerId, int domain, long seq, int state, long requestId, int msgType, int outcomeReason,
                               int finalizedBy, long updatedMs) {

        public static FinalizedRef of(player_inbox_item_row row) {
            return new FinalizedRef(row.getPlayerId(), row.getDomain(), row.getSeq(), row.getState(), row.getRequestId(), row.getMsgType(),
                    row.getOutcomeReason(), row.getFinalizedBy(), row.getUpdatedMs());
        }
    }

    /**
     * 两个对账入口（{@code ReconcilePlayer} 与定时对账）共用的谓词：一条收件箱行的结局在已落盘账本里有没有对应。账本须先过
     * {@link InboxLedgerRules#validate}（环按 seq 严格升序、≤ 32 条）。按顺序：
     * <ol>
     *   <li>不是消费方终结的终态行（PENDING、finalized_by = ADMIN：管理员作废、强制终结）→ 不比（强制终结的行按构造恰好是「环里同 seq 是另一条」）；</li>
     *   <li>seq &gt; decided_through → BEYOND_WATERMARK（四种终态都比，含 SETTLED_UNKNOWN）；</li>
     *   <li>SETTLED_UNKNOWN：原结局本就不在环里（UNKNOWN 不入环）→ 到此为止；</li>
     *   <li>环满 32 条且 seq 早于环头 → 已挤出环，无从核对；</li>
     *   <li>其余必须在环里<b>按 seq 字段</b>找到同一条：request_id、msg_type 相同且结局一致（{@link InboxRules#sameOutcome}：VOIDED ↔ SKIPPED，
     *       REJECTED 另比原因码）。找不到 → MISSING_IN_RING，对不上 → RING_MISMATCH。</li>
     * </ol>
     * 第 5 步的依据：消费方按 seq 升序裁决、每次裁决都入环，水位 = 最后一次裁决的 seq；环不满说明从没裁剪过，所以「首次处置就丢、之后的条目把水位推过去」也抓得到。
     *
     * @param cursor 账本里该域的游标；null = 没有该域（水位 0、环空）
     */
    public static LedgerGap finalizedGap(FinalizedRef ref, InboxDomainCursorState cursor) {
        if (ref.finalizedBy() != PlayerInboxFinalizer.PLAYER_INBOX_FINALIZER_CONSUMER_VALUE || !InboxRules.terminal(ref.state())) {
            return LedgerGap.NONE;
        }
        long decidedThrough = cursor == null ? 0 : cursor.getDecidedThroughSeq();
        if (Long.compareUnsigned(ref.seq(), decidedThrough) > 0) {
            return LedgerGap.BEYOND_WATERMARK;
        }
        if (ref.state() == PlayerInboxItemState.PLAYER_INBOX_ITEM_SETTLED_UNKNOWN_VALUE) {
            return LedgerGap.NONE;
        }
        List<InboxDecisionState> recent = cursor == null ? List.of() : cursor.getRecentList();
        if (recent.size() >= InboxLedgerRules.RECENT_CAPACITY && Long.compareUnsigned(ref.seq(), recent.get(0).getSeq()) < 0) {
            return LedgerGap.NONE;
        }
        for (InboxDecisionState decision : recent) {
            if (decision.getSeq() != ref.seq()) {
                continue;
            }
            if (decision.getRequestId() != ref.requestId() || decision.getMsgType() != ref.msgType()
                    || !InboxRules.sameOutcome(ref.state(), ref.outcomeReason(), decision.getOutcome(), decision.getReason())) {
                return LedgerGap.RING_MISMATCH;
            }
            return LedgerGap.NONE;
        }
        return LedgerGap.MISSING_IN_RING;
    }

    /**
     * {@code ReconcilePlayer} 对一行的判定：收件箱仍 PENDING、账本水位已过这个 seq → PENDING_BUT_DECIDED（确认丢了且玩家一直离线，或 scene 判了
     * MISMATCH——强制模式的入口）；消费方终结的终态行在账本里没有它的结局（{@link #finalizedGap} ≠ NONE）→ FINAL_NOT_IN_LEDGER；其余无差异。
     */
    public static PlayerInboxReconcileDiffKind reconcileDiff(player_inbox_item_row row, InboxDomainCursorState cursor) {
        if (row.getState() == PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE) {
            long decidedThrough = cursor == null ? 0 : cursor.getDecidedThroughSeq();
            return Long.compareUnsigned(row.getSeq(), decidedThrough) <= 0
                    ? PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_PENDING_BUT_DECIDED
                    : PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_DIFF_UNSPECIFIED;
        }
        return finalizedGap(FinalizedRef.of(row), cursor) != LedgerGap.NONE
                ? PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_FINAL_NOT_IN_LEDGER
                : PlayerInboxReconcileDiffKind.PLAYER_INBOX_RECONCILE_DIFF_UNSPECIFIED;
    }
}
