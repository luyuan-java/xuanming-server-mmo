package com.game.common.asset;

import java.util.Objects;

/**
 * 帮会 / 交易资产指令人工终结的共享账本证据判据（对齐主仓 {@code go/shared/assetop/manual_evidence.go} 的 {@code JudgeManual}）：
 * <ul>
 *   <li>判 {@code APPLIED}：玩家已提交资产账本明确记录 {@code APPLIED}，且收据匹配本条请求；</li>
 *   <li>判 {@code ABORTED}：账本明确记录 {@code REJECTED}；或写权行已释放、账本未见该请求、且行已安静（无活租约且距最后发送
 *       超过 {@code max(30min, leaseMs)}，终结须以 CAS 复核这两列）；</li>
 *   <li>部分发放（{@code PARTIAL}）禁止直接人工终结；</li>
 *   <li>账本不可读或无法判定（{@code UNKNOWN}）时绝不许判 {@code ABORTED}。</li>
 * </ul>
 */
public final class ManualAssetOpJudge {

    public static final long MIN_QUIET_MS = 30L * 60_000L;

    public enum LedgerStatus {
        APPLIED,
        REJECTED,
        PARTIAL,
        NOT_SEEN,
        UNKNOWN
    }

    public enum RequestedOutcome {
        APPLIED,
        ABORTED
    }

    public record Evidence(LedgerStatus ledgerStatus,
                           boolean receiptMatches,
                           boolean ownerReleased,
                           boolean hasLiveLease,
                           long lastSendMs,
                           long leaseDurationMs,
                           long nowMs) {
    }

    public record Verdict(boolean allowed, String reason) {
        public static Verdict allow(String reason) {
            return new Verdict(true, reason);
        }

        public static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    private ManualAssetOpJudge() {
    }

    public static Verdict judgeManual(RequestedOutcome requested, Evidence e) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(e, "evidence");
        if (e.ledgerStatus() == LedgerStatus.PARTIAL) {
            return Verdict.deny("部分发放（PARTIAL）禁止人工直接终结，须逐件核对");
        }
        return switch (requested) {
            case APPLIED -> {
                if (e.ledgerStatus() == LedgerStatus.APPLIED && e.receiptMatches()) {
                    yield Verdict.allow("玩家已提交账本确认 APPLIED 且收据匹配本条请求");
                }
                yield Verdict.deny("账本未确认 APPLIED 或收据不匹配，禁止判 applied");
            }
            case ABORTED -> {
                if (e.ledgerStatus() == LedgerStatus.REJECTED) {
                    yield Verdict.allow("玩家已提交账本明确记录 REJECTED，允许判 aborted");
                }
                if (e.ledgerStatus() == LedgerStatus.UNKNOWN) {
                    yield Verdict.deny("账本状态无法判定（UNKNOWN），严禁判 aborted");
                }
                if (e.ledgerStatus() == LedgerStatus.APPLIED) {
                    yield Verdict.deny("账本已记录 APPLIED，严禁判 aborted");
                }
                // NOT_SEEN：须写权行已释放 + 无活租约 + 安静满 max(30min, leaseDuration)
                if (!e.ownerReleased()) {
                    yield Verdict.deny("写权行尚未释放，禁止凭账本未见判 aborted");
                }
                if (e.hasLiveLease()) {
                    yield Verdict.deny("操作行仍持有活租约，禁止判 aborted");
                }
                long quietWindow = Math.max(MIN_QUIET_MS, Math.max(0L, e.leaseDurationMs()));
                if (e.lastSendMs() <= 0 || e.nowMs() - e.lastSendMs() < quietWindow) {
                    yield Verdict.deny("距最后一次发送未满安静窗口 max(30min, lease)，禁止判 aborted");
                }
                yield Verdict.allow("写权已释放、账本未见且行已安静，允许以 CAS 复核后判 aborted");
            }
        };
    }
}
