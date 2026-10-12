package com.game.data.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.NamespaceDatabaseCheck;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 回灌工具的部署命名空间与库名核对（缺省 JVM，命名空间钉在 game；库用 H2）：显式定格并打印命名空间；连上库后、建运维表之前核对
 * {@code DATABASE()}，期望值缺省 = 命名空间、{@code --expect-database} 覆盖；不一致退出 2、不建表、报错里不带 URL；{@code --dry-run} 不做这项检查。
 */
class AuditFallbackReplayNamespaceTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(List<String> argv, Map<String, String> env) {
        return AuditFallbackReplay.run(argv, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static String h2Url() {
        return "jdbc:h2:mem:replayns" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    private static boolean hasTable(String url, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection(url);
             ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    @Test
    void 库名与命名空间不一致_退出2_不建运维表_报错不带URL(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("fallback.log");
        Files.writeString(log, "unrelated log line\n");
        String url = h2Url();
        try (Connection keepAlive = DriverManager.getConnection(url)) {
            String actual = NamespaceDatabaseCheck.currentDatabase(keepAlive);
            assertThat(actual).isNotEqualTo("game");

            int code = run(List.of("--file", log.toString()), Map.of(AuditFallbackReplay.DEFAULT_URL_ENV, url));

            assertThat(code).isEqualTo(2);
            assertThat(err()).contains("部署命名空间 game.namespace=game").contains("库名核对失败").contains("DATABASE()=" + actual)
                    .contains("期望 game").doesNotContain("jdbc:");
            assertThat(hasTable(url, "audit_replay_line")).as("核对在 OpsTables.sync 之前").isFalse();

            err.reset();
            assertThat(run(List.of("--file", log.toString(), "--expect-database", "other_db"), Map.of(AuditFallbackReplay.DEFAULT_URL_ENV, url)))
                    .isEqualTo(2);
            assertThat(err()).contains("期望 other_db").contains("--expect-database");
            assertThat(hasTable(url, "audit_replay_line")).isFalse();

            err.reset();
            int matched = run(List.of("--file", log.toString(), "--expect-database", actual), Map.of(AuditFallbackReplay.DEFAULT_URL_ENV, url));
            assertThat(err()).as("期望库名给对时核对通过、往下走").doesNotContain("库名核对失败");
            assertThat(matched).isNotEqualTo(2);
        }
    }

    @Test
    void dry_run不连库也不核对库名_空的期望库名是参数错误(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("fallback.log");
        Files.writeString(log, "unrelated log line\n");

        assertThat(run(List.of("--file", log.toString(), "--dry-run", "--expect-database", "whatever"), Map.of())).isEqualTo(0);
        assertThat(err()).contains("部署命名空间 game.namespace=game").doesNotContain("库名核对失败");
        assertThat(run(List.of("--file", log.toString(), "--expect-database", " "), Map.of())).isEqualTo(2);
        assertThat(run(List.of("--file", log.toString(), "--expect-database"), Map.of())).isEqualTo(2);
    }
}
