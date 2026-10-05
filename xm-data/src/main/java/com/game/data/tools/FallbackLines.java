package com.game.data.tools;

import com.game.data.txlog.TransactionLogRow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计兜底日志 {@code xm.audit.fallback} 的行解析（格式见 xm-scene 的 {@code AuditFallbackLog.transaction}：键值对、{@code extra=} 在行尾、
 * 取到行末）。日志前缀（时间、级别、线程、logger 名）不管长什么样，只认 {@code "transaction result="} 起的那一段。
 *
 * <p>快照行（{@code "snapshot result="}）只有元数据、没有玩法数据本体，<b>不能回灌</b>（有意如此，data-ops-spec §2.4），单独计数。
 */
public final class FallbackLines {

    /** 一行的解析结果。 */
    public sealed interface Parsed permits Transaction, Snapshot, Other, Malformed {
    }

    /** 一条资产流水（{@code row.txId()} 为 0 = 生产方没发出号：未核对 / 发不出号 / 队列满，回灌时要发新号）。 */
    public record Transaction(String result, TransactionLogRow row) implements Parsed {
    }

    /** 快照兜底行（不回灌）。 */
    public record Snapshot() implements Parsed {
    }

    /** 不是兜底记录的行（日志里的其他内容、空行）。 */
    public record Other() implements Parsed {
    }

    /** 像兜底流水行、但解析不了。 */
    public record Malformed(String why) implements Parsed {
    }

    static final String TRANSACTION_MARK = "transaction result=";
    static final String SNAPSHOT_MARK = "snapshot result=";

    private static final Pattern TRANSACTION = Pattern.compile("transaction result=(\\S+) tx_id=(\\d+) time_ms=(-?\\d+) "
            + "reason=(-?\\d+) kind=(-?\\d+) from=(\\d+) to=(\\d+) currency_type=(-?\\d+) delta=(-?\\d+) before=(\\d+) "
            + "after=(\\d+) item_uuid=(\\d+) item_config_id=(\\d+) item_quantity=(\\d+) correlation_id=(\\d+) zone=(\\d+) "
            + "extra=(.*)");

    private FallbackLines() {
    }

    public static Parsed parse(String rawLine) {
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        int at = line.indexOf(TRANSACTION_MARK);
        if (at < 0) {
            return line.contains(SNAPSHOT_MARK) ? new Snapshot() : new Other();
        }
        Matcher m = TRANSACTION.matcher(line.substring(at));
        if (!m.matches()) {
            return new Malformed("字段不全或格式不对");
        }
        try {
            int kind = (int) Long.parseLong(m.group(5));
            if (kind == 0) {
                return new Malformed("kind = 0（种类未指定，消费端同样会跳过）");
            }
            TransactionLogRow row = new TransactionLogRow(
                    Long.parseUnsignedLong(m.group(2)),
                    Long.parseLong(m.group(3)),
                    (int) Long.parseLong(m.group(4)),
                    kind,
                    Long.parseUnsignedLong(m.group(6)),
                    Long.parseUnsignedLong(m.group(7)),
                    (int) Long.parseLong(m.group(8)),
                    Long.parseLong(m.group(9)),
                    Long.parseUnsignedLong(m.group(10)),
                    Long.parseUnsignedLong(m.group(11)),
                    Long.parseUnsignedLong(m.group(12)),
                    Integer.parseUnsignedInt(m.group(13)),
                    Integer.parseUnsignedInt(m.group(14)),
                    Long.parseUnsignedLong(m.group(15)),
                    m.group(17),
                    Integer.parseUnsignedInt(m.group(16)));
            return new Transaction(m.group(1), row);
        } catch (NumberFormatException e) {
            return new Malformed("数值越界：" + e.getMessage());
        }
    }
}
