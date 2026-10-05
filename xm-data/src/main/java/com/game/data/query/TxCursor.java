package com.game.data.query;

import com.game.data.ops.OpsException;
import com.game.data.store.TransactionLogEntry;

/**
 * 流水查询的键集分页游标 {@code <timeMs>:<txId>}（txId 无符号十进制）：下一页只要（时间、流水号）严格大于它的行。
 * 不用 OFFSET（基线 QueryLog 的深分页，H14）。
 */
public record TxCursor(long timeMs, long txId) {

    public static TxCursor of(TransactionLogEntry e) {
        return new TxCursor(e.getTimeMs(), e.getTxId());
    }

    /** 解析；null / 空 = 从头。格式不对 → 400。 */
    public static TxCursor parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            throw OpsException.badRequest("after 游标格式是 <timeMs>:<txId>");
        }
        try {
            long time = Long.parseLong(raw.substring(0, colon));
            String tx = raw.substring(colon + 1);
            if (tx.startsWith("+") || tx.startsWith("-")) {
                throw new NumberFormatException(tx);
            }
            return new TxCursor(time, Long.parseUnsignedLong(tx));
        } catch (NumberFormatException e) {
            throw OpsException.badRequest("after 游标格式是 <timeMs>:<txId>");
        }
    }

    public String format() {
        return timeMs + ":" + Long.toUnsignedString(txId);
    }

    /** 与一行比较（时间、流水号无符号）。 */
    public int compareTo(TransactionLogEntry e) {
        int c = Long.compare(timeMs, e.getTimeMs());
        return c != 0 ? c : Long.compareUnsigned(txId, e.getTxId());
    }
}
