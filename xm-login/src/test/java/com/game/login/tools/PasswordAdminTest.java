package com.game.login.tools;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 存量口令迁移工具：只给已存在、还没有哈希的账号写一次；口令从标准输入读两行；不改密、不注册。内存 H2（MySQL 模式）。 */
class PasswordAdminTest {

    private final String url = "jdbc:h2:mem:pa" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void schema() throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE account (account VARCHAR(64) NOT NULL PRIMARY KEY, created_at BIGINT NOT NULL,"
                    + " password_hash VARCHAR(255) NULL)");
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

    private int run(String stdin, String... args) {
        return PasswordAdmin.run(List.of(args), Map.of(PasswordAdmin.DEFAULT_URL_ENV, url), null,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
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
        String goDsn = "root:TOPSECRET@tcp(127.0.0.1:3306)/xm_java";
        int notJdbc = PasswordAdmin.run(List.of("--account", "robot_0001", "--password-stdin"),
                Map.of(PasswordAdmin.DEFAULT_URL_ENV, goDsn), null,
                new ByteArrayInputStream("p\np\n".getBytes(StandardCharsets.UTF_8)), DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(notJdbc).isEqualTo(1);
        String badUrl = "jdbc:mysq://127.0.0.1:3306/xm_java?user=root&password=TOPSECRET";
        int driverError = PasswordAdmin.run(List.of("--account", "robot_0001", "--password-stdin"),
                Map.of(PasswordAdmin.DEFAULT_URL_ENV, badUrl), null,
                new ByteArrayInputStream("p\np\n".getBytes(StandardCharsets.UTF_8)), DriverManager::getConnection,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(driverError).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("08001").doesNotContain("TOPSECRET");
    }
}
