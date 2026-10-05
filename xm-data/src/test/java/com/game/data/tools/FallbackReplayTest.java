package com.game.data.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.data.store.TransactionLogEntry;
import com.game.data.store.TransactionLogQuery;
import com.game.data.testing.DataSqlFixture;
import com.game.data.tools.FallbackLines.Malformed;
import com.game.data.tools.FallbackLines.Other;
import com.game.data.tools.FallbackLines.Snapshot;
import com.game.data.tools.FallbackLines.Transaction;
import com.game.data.txlog.TransactionLogRow;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 兜底日志回灌（G9）：行格式与 xm-scene 的 AuditFallbackLog 逐字段对齐；原号回灌幂等、tx_id = 0 的行发新号、
 * 同一文件重跑不重复入库、快照行不回灌、发不出号时中止且已完成的行保留。数据库部分缺省 H2，{@code -Dxm.it.mysql} 时连真 MySQL。
 */
class FallbackReplayTest {

    /** 与 xm-scene AuditFallbackLog.transaction 相同的消息格式（前缀是 Spring Boot 缺省控制台格式）。 */
    static String line(String result, String txId, long timeMs, int reason, int kind, String from, String to,
                       int currencyType, long delta, String before, String after, String uuid, String config, String qty,
                       String correlation, String zone, String extra) {
        return "2026-10-05T03:00:00.000+08:00  WARN 1234 --- [xm-scene] [scene-audit] xm.audit.fallback                       : "
                + "transaction result=" + result + " tx_id=" + txId + " time_ms=" + timeMs + " reason=" + reason + " kind="
                + kind + " from=" + from + " to=" + to + " currency_type=" + currencyType + " delta=" + delta + " before="
                + before + " after=" + after + " item_uuid=" + uuid + " item_config_id=" + config + " item_quantity=" + qty
                + " correlation_id=" + correlation + " zone=" + zone + " extra=" + extra;
    }

    @Test
    void 解析_字段逐个对齐_无符号大值_extra取到行末_CRLF_快照行与其他行() {
        String raw = line("delivery_failed", "18446744073709551614", 1700, 25, 1, "0", "1001", 0, 110,
                "9223372036854775807", "18446744073709551615", "0", "0", "0", "9223372036854775808", "3",
                "{\"a\": \"x y=z\"}") + "\r";

        Transaction t = (Transaction) FallbackLines.parse(raw);

        TransactionLogRow r = t.row();
        assertThat(t.result()).isEqualTo("delivery_failed");
        assertThat(r.txId()).isEqualTo(-2L);
        assertThat(r.timeMs()).isEqualTo(1700);
        assertThat(r.reason()).isEqualTo(25);
        assertThat(r.kind()).isEqualTo(1);
        assertThat(r.toPlayer()).isEqualTo(1001);
        assertThat(r.currencyDelta()).isEqualTo(110);
        assertThat(r.balanceBefore()).isEqualTo(Long.MAX_VALUE);
        assertThat(r.balanceAfter()).isEqualTo(-1L);
        assertThat(r.correlationId()).isEqualTo(Long.MIN_VALUE);
        assertThat(r.zoneId()).isEqualTo(3);
        assertThat(r.extra()).isEqualTo("{\"a\": \"x y=z\"}");

        assertThat(FallbackLines.parse("... xm.audit.fallback : snapshot result=queue_full snapshot_id=0 player=1"))
                .isInstanceOf(Snapshot.class);
        assertThat(FallbackLines.parse("2026-10-05 INFO something else")).isInstanceOf(Other.class);
        assertThat(FallbackLines.parse("x transaction result=unverified tx_id=abc")).isInstanceOf(Malformed.class);
        assertThat(FallbackLines.parse(line("unverified", "0", 1, 9, 0, "0", "1", 0, 1, "0", "1", "0", "0", "0", "0", "1", "")))
                .as("kind = 0 消费端也跳过").isInstanceOf(Malformed.class);
    }

    private static byte[] file(String... lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String gold(String txId, long timeMs, long delta, String extra) {
        return line("unverified", txId, timeMs, 9, 1, "0", "1001", 0, delta, "0", Long.toString(delta), "0", "0", "0", "0",
                "1", extra);
    }

    @Test
    void 回灌_原号幂等_零号发新号_重跑不重复_快照行不回灌_发不出号中止且已完成的保留() throws Exception {
        try (DataSqlFixture db = DataSqlFixture.create(); Connection conn = db.dataSource.getConnection()) {
            AtomicLong next = new AtomicLong(5_000);
            boolean[] idsUp = {true};
            FallbackReplayer replayer = new FallbackReplayer(conn, db.ops,
                    () -> idsUp[0] ? OptionalLong.of(next.incrementAndGet()) : OptionalLong.empty(), () -> 42L);
            // 库里已有 tx 77（Kafka 已落过）：回灌记 duplicate、不改那一行
            db.jdbc().update("INSERT INTO transaction_log (tx_id, time_ms, reason, kind, to_player, currency_delta, "
                    + "extra, zone_id, ingested_at) VALUES (77, 1, 9, 1, 1001, 5, 'kafka', 1, 1)");
            byte[] content = file(gold("77", 100, 5, "dup"), gold("0", 200, 6, "a"),
                    "... xm.audit.fallback : snapshot result=no_id snapshot_id=0 player=1001", "unrelated log line",
                    gold("0", 300, 7, "b"), gold("88", 400, 8, "c"));

            FallbackReplayer.Stats first = replayer.replay(content);

            assertThat(first.ok()).isTrue();
            assertThat(first.lines()).isEqualTo(6);
            assertThat(first.transactions()).isEqualTo(4);
            assertThat(first.snapshotsSkipped()).isEqualTo(1);
            assertThat(first.inserted()).isEqualTo(3);
            assertThat(first.duplicate()).isEqualTo(1);
            assertThat(first.newIds()).isEqualTo(2);
            List<TransactionLogEntry> rows = db.txlog.query(TransactionLogQuery.builder().toPlayer(1001L).build());
            assertThat(rows).extracting(TransactionLogEntry::getTxId).containsExactly(77L, 5001L, 5002L, 88L);
            assertThat(rows.get(0).getExtra()).as("库里已有的行不动").isEqualTo("kafka");
            assertThat(rows.get(1).getExtra()).isEqualTo("a");
            assertThat(rows.get(1).getIngestedAt()).isEqualTo(42L);
            assertThat(db.count("audit_replay_line")).isEqualTo(4);

            FallbackReplayer.Stats again = replayer.replay(content);
            assertThat(again.alreadyReplayed()).isEqualTo(4);
            assertThat(again.inserted() + again.newIds()).isZero();
            assertThat(db.count("transaction_log")).as("同一文件重跑不重复入库").isEqualTo(4);

            idsUp[0] = false;
            byte[] other = file(gold("99", 500, 9, "d"), gold("0", 600, 10, "e"), gold("100", 700, 11, "f"));
            FallbackReplayer.Stats aborted = replayer.replay(other);
            assertThat(aborted.aborted()).isTrue();
            assertThat(aborted.ok()).isFalse();
            assertThat(aborted.inserted()).as("中止前的行已提交").isEqualTo(1);
            assertThat(db.count("transaction_log")).isEqualTo(5);

            idsUp[0] = true;
            FallbackReplayer.Stats resumed = replayer.replay(other);
            assertThat(resumed.alreadyReplayed()).isEqualTo(1);
            assertThat(resumed.inserted()).isEqualTo(2);
            assertThat(db.count("transaction_log")).isEqualTo(7);
        }
    }

    @Test
    void 命令行_dry_run只解析_参数错误回2_解析不了的行回1(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("fallback.log");
        Files.write(log, file(gold("0", 1, 1, ""), "x transaction result=unverified tx_id=oops"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = AuditFallbackReplay.run(List.of("--file", log.toString(), "--dry-run"), Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).as("有解析不了的行").isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("transactions=1", "malformed=1", "第 2 行");
        assertThat(AuditFallbackReplay.run(List.of("--bogus"), Map.of(), new PrintStream(out), new PrintStream(err)))
                .isEqualTo(2);
        assertThat(AuditFallbackReplay.run(List.of("--file", log.toString()), Map.of(), new PrintStream(out),
                new PrintStream(err))).as("没给 JDBC URL").isEqualTo(1);
    }
}
