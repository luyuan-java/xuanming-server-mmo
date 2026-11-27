package com.game.player.store;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 混版世代闸与确定性拒存分类（对齐 r2-ps#11 与第一轮 §4 第 2 条）：
 * <ul>
 *   <li>存档服务遇到本版本不认识的行种（新版本写入）时抛出可重试异常 {@link UnknownSaveRowKindException}（「版本太新」），
 *       绝不当成非法请求永久拒绝或静默丢弃；</li>
 *   <li>确定性拒存按 {@link SaveRejectionKind}（{@code too_large} / {@code invalid} / {@code other}）分类并供低基数指标与 critical 告警统计。</li>
 * </ul>
 */
public final class SaveRowGuard {

    public enum SaveRejectionKind {
        TOO_LARGE("too_large"),
        INVALID("invalid"),
        OTHER("other");

        private final String tag;

        SaveRejectionKind(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** 可重试的「版本太新」异常（调用方应稍后重试或路由到已升级的存档服务副本，不得当非法请求丢弃）。 */
    public static final class UnknownSaveRowKindException extends IllegalStateException {
        private final int rowKind;

        public UnknownSaveRowKindException(int rowKind) {
            super("存档行种超出本版本识别范围（版本太新，可重试） row_kind=" + rowKind);
            this.rowKind = rowKind;
        }

        public int rowKind() {
            return rowKind;
        }

        public boolean retryable() {
            return true;
        }
    }

    /** TiDB 单条记录上限 / MySQL max_allowed_packet 常见错误码（1153 Packet too big、1118 Row size too large、8025 TiDB entry too large）。 */
    public static SaveRejectionKind classifyRejection(Throwable error) {
        if (error == null) {
            return SaveRejectionKind.OTHER;
        }
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof SQLException sql) {
                int code = sql.getErrorCode();
                if (code == 1153 || code == 1118 || code == 8025) {
                    return SaveRejectionKind.TOO_LARGE;
                }
                if (code == 1406 || code == 1366) {
                    return SaveRejectionKind.INVALID;
                }
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("entry too large") || lower.contains("packet too big")
                        || lower.contains("max_allowed_packet") || lower.contains("too large")) {
                    return SaveRejectionKind.TOO_LARGE;
                }
                if (lower.contains("invalid") || lower.contains("corrupt")) {
                    return SaveRejectionKind.INVALID;
                }
            }
            cur = cur.getCause();
        }
        return SaveRejectionKind.OTHER;
    }

    /** 校验存档行种是否在已知集合内；未知行种抛出可重试 {@link UnknownSaveRowKindException}。 */
    public static void requireKnownRowKind(int rowKind, java.util.Set<Integer> knownKinds) {
        if (knownKinds == null || !knownKinds.contains(rowKind)) {
            throw new UnknownSaveRowKindException(rowKind);
        }
    }

    /**
     * 战斗道具预留组件存在性门禁（PARITY 2026-11-10）：要求预留组件落盘时，缺失 {@code battle_reservations}
     * 字段的存档整份拒存，防止降级/漏序列化的写回把已登记的战斗预留静默抹除。
     */
    public static void requireBattleReservationsPresent(com.game.player.store.state.PlayerState state) {
        if (state == null || !state.hasBattleReservations()) {
            throw new IllegalArgumentException("player_state missing required battle_reservations component");
        }
    }

    private SaveRowGuard() {
    }
}
