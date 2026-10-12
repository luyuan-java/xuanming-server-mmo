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
 * 回灌工具的部署命名空间与库名核对（库用 H2）：命名空间与服务进程同口径绑定（run() 注入的系统属性 {@code game.namespace} 优先于环境变量
 * {@code GAME_NAMESPACE}，没配取 game），连同来源打印出来，非法值退出 2；
 * 连上库后、建运维表之前核对 {@code DATABASE()}，期望值缺省 = 命名空间、{@code --expect-database} 覆盖；不一致退出 2、不建表、报错里不带 URL；
 * {@code --dry-run} 不做这项检查。
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
    void 命名空间取自环境变量GAME_NAMESPACE_库名按它核对(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("fallback.log");
        Files.writeString(log, "unrelated log line\n");
        String url = h2Url();
        try (Connection keepAlive = DriverManager.getConnection(url)) {
            String actual = NamespaceDatabaseCheck.currentDatabase(keepAlive);

            int code = run(List.of("--file", log.toString()), Map.of(AuditFallbackReplay.DEFAULT_URL_ENV, url, "GAME_NAMESPACE", "nstest"));

            assertThat(code).isEqualTo(2);
            assertThat(err()).contains("部署命名空间 game.namespace=nstest（来源 \"GAME_NAMESPACE\" from property source \"systemEnvironment\"）")
                    .contains("game.namespace=nstest，当前连接的库 DATABASE()=" + actual).contains("期望 nstest");
            assertThat(hasTable(url, "audit_replay_line")).isFalse();
        }
    }

    @Test
    void 系统属性game_namespace与服务进程同样认_且优先于环境变量_没配取缺省(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("fallback.log");
        Files.writeString(log, "unrelated log line\n");
        // 以前只认精确的环境变量 GAME_NAMESPACE，-Dgame.namespace 被静默忽略、回落到 game（评审意见）；现在与服务进程同口径
        int code = AuditFallbackReplay.run(List.of("--file", log.toString(), "--dry-run"), Map.of("GAME_NAMESPACE", "fromenv"),
                Map.of("game.namespace", "fromprop"), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(0);
        assertThat(err()).contains("部署命名空间 game.namespace=fromprop（来源 \"game.namespace\" from property source \"systemProperties\"）");

        err.reset();
        assertThat(AuditFallbackReplay.run(List.of("--file", log.toString(), "--dry-run"), Map.of(), Map.of("game.namespace", "Bad"),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))).as("-D 给的非法值同样拒绝")
                .isEqualTo(2);
        assertThat(err()).contains("\"Bad\"").contains("systemProperties");

        err.reset();
        assertThat(run(List.of("--file", log.toString(), "--dry-run"), Map.of())).isEqualTo(0);
        assertThat(err()).contains("部署命名空间 game.namespace=game（来源 缺省）");
    }

    @Test
    void 命名空间非法_连库读文件之前退出2_报错点名原值(@TempDir Path dir) {
        Path missing = dir.resolve("不存在.log");
        for (String bad : List.of("Bad", "", " game", "mysql", "a".repeat(29))) {
            err.reset();
            int code = run(List.of("--file", missing.toString()), Map.of(AuditFallbackReplay.DEFAULT_URL_ENV, "jdbc:h2:mem:never",
                    "GAME_NAMESPACE", bad));
            assertThat(code).as("取值 \"%s\"", bad).isEqualTo(2);
            assertThat(err()).as("取值 \"%s\"", bad).contains("GAME_NAMESPACE").contains("\"" + bad + "\"")
                    .doesNotContain("读文件失败").doesNotContain("部署命名空间 game.namespace=");
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
