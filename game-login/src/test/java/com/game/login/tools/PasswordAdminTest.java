package com.game.login.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.NamespaceDatabaseCheck;
import com.game.login.auth.Argon2id;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 存量口令迁移工具：只给已存在、还没有哈希的账号写一次；口令从标准输入读两行；不改密、不注册。内存 H2（MySQL 模式，库名转小写）。
 * 内存库名本身就是合法的部署命名空间，常规用例经环境变量 {@code GAME_NAMESPACE} 给它（规格：库名 = 命名空间），库名核对见最后几个用例。
 */
class PasswordAdminTest {

    /** 内存库名 = 本用例的部署命名空间（小写字母开头、小写十六进制，22 个字符）。 */
    private final String database = "pa" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final String url = "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void schema() throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE account (account VARCHAR(64) NOT NULL PRIMARY KEY, created_at BIGINT NOT NULL,"
                    + " password_hash VARCHAR(255) NULL)");
            assertThat(NamespaceDatabaseCheck.currentDatabase(c)).isEqualTo(database);
        }
        insert("robot_0001", null);
        insert("robot_0002", "$argon2id$v=19$x");
    }

    private void insert(String account, String hash) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO account (account, created_at, password_hash) VALUES (?, 1, ?)")) {
            s.setString(1, account);
            s.setString(2, hash);
            s.executeUpdate();
        }
    }

    /** 环境：JDBC URL + {@code GAME_NAMESPACE} = 内存库名。 */
    private int run(String stdin, String... args) {
        return runIn(Map.of(PasswordAdmin.DEFAULT_URL_ENV, url, "GAME_NAMESPACE", database),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), args);
    }

    private int runIn(Map<String, String> env, ByteArrayInputStream stdin, String... args) {
        return PasswordAdmin.run(List.of(args), env, null, stdin, DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String hashOf(String account) throws SQLException {
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement s = c.prepareStatement("SELECT password_hash FROM account WHERE account = ?")) {
            s.setString(1, account);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void 给既有账号写入Argon2id哈希_能校验() throws SQLException {
        assertThat(run("s3cret\r\ns3cret\n", "--account", "robot_0001", "--password-stdin")).isZero();
        String hash = hashOf("robot_0001");
        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=2$");
        assertThat(Argon2id.verify("s3cret", hash)).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("s3cret");
    }

    @Test
    void 已有哈希的账号不改密_不存在的账号不注册() throws SQLException {
        assertThat(run("p\np\n", "--account", "robot_0002", "--password-stdin")).isEqualTo(1);
        assertThat(hashOf("robot_0002")).isEqualTo("$argon2id$v=19$x");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("不支持改密");
        assertThat(run("p\np\n", "--account", "robot_9999", "--password-stdin")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("不注册新账号");
    }

    @Test
    void 口令两行不一致_行数不对_不是终端又没说stdin_都失败且不写库() throws SQLException {
        assertThat(run("a\nb\n", "--account", "robot_0001", "--password-stdin")).isEqualTo(1);
        assertThat(run("a\n", "--account", "robot_0001", "--password-stdin")).isEqualTo(1);
        assertThat(run("a\na\na\n", "--account", "robot_0001", "--password-stdin")).isEqualTo(1);
        assertThat(run("a\na\n", "--account", "robot_0001")).as("没有终端").isEqualTo(1);
        assertThat(hashOf("robot_0001")).isNull();
    }

    @Test
    void 参数与环境不对() {
        assertThat(run("", "--bogus")).isEqualTo(2);
        assertThat(run("", "--password-stdin", "--account")).as("缺值").isEqualTo(2);
        assertThat(run("", "--account", " bad", "--password-stdin")).as("账号不合法同基线回 1").isEqualTo(1);
        assertThat(run("", "--account", "robot_0001", "--jdbc-url-env", " ", "--password-stdin")).isEqualTo(1);
        int missingUrl = PasswordAdmin.run(List.of("--account", "robot_0001", "--password-stdin"), Map.of(), null,
                new ByteArrayInputStream(new byte[0]), DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(missingUrl).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(PasswordAdmin.DEFAULT_URL_ENV);
    }

    @Test
    void 连接串与数据库错误都不回显库口令() {
        String goDsn = "root:TOPSECRET@tcp(127.0.0.1:3306)/game";
        int notJdbc = PasswordAdmin.run(List.of("--account", "robot_0001", "--password-stdin"),
                Map.of(PasswordAdmin.DEFAULT_URL_ENV, goDsn), null,
                new ByteArrayInputStream("p\np\n".getBytes(StandardCharsets.UTF_8)), DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(notJdbc).isEqualTo(1);
        String badUrl = "jdbc:mysq://127.0.0.1:3306/game?user=root&password=TOPSECRET";
        int driverError = PasswordAdmin.run(List.of("--account", "robot_0001", "--password-stdin"),
                Map.of(PasswordAdmin.DEFAULT_URL_ENV, badUrl), null,
                new ByteArrayInputStream("p\np\n".getBytes(StandardCharsets.UTF_8)), DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(driverError).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("08001").doesNotContain("TOPSECRET");
    }

    @Test
    void 命名空间与库名一致_打印命名空间与来源() {
        assertThat(run("s3cret\ns3cret\n", "--account", "robot_0001", "--password-stdin")).isZero();
        assertThat(err()).contains("部署命名空间 game.namespace=" + database + "（来源 环境变量 GAME_NAMESPACE）");
    }

    @Test
    void 没设GAME_NAMESPACE取game_与库名不一致_退出2_不读口令不写库_报错不带URL() throws SQLException {
        ByteArrayInputStream stdin = new ByteArrayInputStream("s3cret\ns3cret\n".getBytes(StandardCharsets.UTF_8));
        int code = runIn(Map.of(PasswordAdmin.DEFAULT_URL_ENV, url), stdin, "--account", "robot_0001", "--password-stdin");

        assertThat(code).isEqualTo(2);
        assertThat(err()).contains("部署命名空间 game.namespace=game（来源 缺省）").contains("库名核对失败")
                .contains("DATABASE()=" + database).contains("期望 game").contains("--expect-database").doesNotContain("jdbc:");
        assertThat(stdin.available()).as("核对在读口令之前").isEqualTo("s3cret\ns3cret\n".length());
        assertThat(hashOf("robot_0001")).isNull();
    }

    @Test
    void 应急开关给出真实库名可以放行_给错或为空退出2_不写库() throws SQLException {
        Map<String, String> noNamespace = Map.of(PasswordAdmin.DEFAULT_URL_ENV, url);
        assertThat(runIn(noNamespace, stdin("p\np\n"), "--expect-database", "other_db", "--account", "robot_0001", "--password-stdin"))
                .isEqualTo(2);
        assertThat(err()).contains("期望 other_db");
        assertThat(runIn(noNamespace, stdin("p\np\n"), "--expect-database", " ", "--account", "robot_0001", "--password-stdin"))
                .isEqualTo(2);
        assertThat(runIn(noNamespace, stdin("p\np\n"), "--account", "robot_0001", "--password-stdin", "--expect-database"))
                .as("缺值").isEqualTo(2);
        assertThat(hashOf("robot_0001")).isNull();

        assertThat(runIn(noNamespace, stdin("p\np\n"), "--expect-database", database, "--account", "robot_0001", "--password-stdin"))
                .isZero();
        assertThat(hashOf("robot_0001")).startsWith("$argon2id$");
    }

    @Test
    void 命名空间非法或为空串_退出2_不连库不读口令() throws SQLException {
        for (String bad : List.of("Bad", "", " game", "mysql")) {
            ByteArrayInputStream stdin = stdin("p\np\n");
            assertThat(runIn(Map.of(PasswordAdmin.DEFAULT_URL_ENV, url, "GAME_NAMESPACE", bad), stdin,
                    "--account", "robot_0001", "--password-stdin")).as(bad).isEqualTo(2);
            assertThat(stdin.available()).as(bad).isEqualTo(4);
        }
        assertThat(err()).contains("GAME_NAMESPACE").contains("\"Bad\"").contains("\"\"").contains("\" game\"");
        assertThat(hashOf("robot_0001")).isNull();
    }

    private static ByteArrayInputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
