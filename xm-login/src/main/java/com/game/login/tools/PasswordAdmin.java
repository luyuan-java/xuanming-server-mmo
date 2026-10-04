package com.game.login.tools;

import com.game.login.auth.Argon2id;
import com.game.login.auth.ProductionPasswordAuthenticator;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 存量口令迁移工具（同 mmorpg {@code go/login/cmd/password_admin}）：只给<b>已存在</b>、还没有 Argon2id 哈希的账号写一次哈希；
 * 不注册新账号、不改密 / 重置，口令不进命令行参数、日志与 SQL 文件。
 *
 * <p>用法（在仓库根目录，xm-login 已打包）：
 * <pre>
 * export XM_PASSWORD_ADMIN_JDBC_URL='jdbc:mysql://127.0.0.1:3306/xm_java?user=...&amp;password=...'
 * java -cp xm-login/target/xm-login-0.1.0-SNAPSHOT.jar -Dloader.main=com.game.login.tools.PasswordAdmin \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --account &lt;账号&gt; [--password-stdin]
 * </pre>
 * 口令从终端读两遍（不回显）；{@code --password-stdin} 从标准输入读恰好两行（批处理用）。JDBC URL（含库名与账号口令）只从环境变量读，
 * 变量名可用 {@code --jdbc-url-env} 换。退出码 0 成功、1 失败、2 参数解析错误（未知参数 / 缺值），同基线。
 */
public final class PasswordAdmin {

    static final String DEFAULT_URL_ENV = "XM_PASSWORD_ADMIN_JDBC_URL";
    /** 建连与每条语句的超时（同基线 10 s）。 */
    private static final int TIMEOUT_SECONDS = 10;

    /** 连接来源（测试换成内存库）。 */
    @FunctionalInterface
    interface Connections {
        Connection open(String jdbcUrl) throws SQLException;
    }

    private PasswordAdmin() {
    }

    public static void main(String[] args) {
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        int code = run(Arrays.asList(args), System.getenv(), System.console(), System.in, DriverManager::getConnection,
                System.out, System.err);
        System.exit(code);
    }

    static int run(List<String> args, Map<String, String> env, Console console, InputStream stdin,
                   Connections connections, PrintStream out, PrintStream err) {
        String account = null;
        String urlEnv = DEFAULT_URL_ENV;
        boolean passwordStdin = false;
        // 参数解析错误回 2（同基线 Go flag 包），其余失败回 1
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--account", "--jdbc-url-env" -> {
                    if (i + 1 >= args.size()) {
                        err.println("参数缺值：" + arg);
                        return 2;
                    }
                    String value = args.get(++i);
                    if (arg.equals("--account")) {
                        account = value;
                    } else {
                        urlEnv = value;
                    }
                }
                case "--password-stdin" -> passwordStdin = true;
                default -> {
                    err.println("未知参数：" + arg);
                    return 2;
                }
            }
        }
        if (!ProductionPasswordAuthenticator.validAccount(account)) {
            err.println("账号不合法：须非空、首尾无空白、不超过 64 个字符");
            return 1;
        }
        if (urlEnv.isBlank()) {
            err.println("--jdbc-url-env 不能为空");
            return 1;
        }
        String url = env.get(urlEnv);
        if (url == null || url.isBlank()) {
            err.println("环境变量 " + urlEnv + "（JDBC URL）缺失或为空");
            return 1;
        }
        if (!url.startsWith("jdbc:")) {
            // 不回显取值（里面有库口令）
            err.println("环境变量 " + urlEnv + " 不是 JDBC URL（应以 jdbc:mysql:// 开头）");
            return 1;
        }
        try {
            String password = passwordStdin ? readPasswordLines(stdin) : readPasswordTwice(console);
            String encoded = Argon2id.hash(password, new SecureRandom());
            migrate(connections.open(url), account, encoded);
            out.println("已为既有账号写入口令哈希");
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("失败：" + e.getMessage());
            return 1;
        } catch (SQLException e) {
            // 不打异常消息：驱动的消息可能原样带 URL（「No suitable driver found for <url>」），里面有库口令
            err.println("失败：数据库出错 SQLState=" + e.getSQLState() + " errorCode=" + e.getErrorCode() + " "
                    + e.getClass().getSimpleName());
            return 1;
        }
    }

    /** 一个事务里：锁住账号行 → 恰好一行、还没有 Argon2id 哈希 → 写入 → 恰好影响一行 → 提交。 */
    static void migrate(Connection connection, String account, String encoded) throws SQLException {
        try (connection) {
            connection.setAutoCommit(false);
            int rows = 0;
            boolean alreadyMigrated = false;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT account, password_hash FROM account WHERE account = ? FOR UPDATE")) {
                select.setQueryTimeout(TIMEOUT_SECONDS);
                select.setString(1, account);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rows++;
                        String current = rs.getString(2);
                        alreadyMigrated = current != null && current.startsWith("$argon2id$");
                    }
                }
            }
            if (rows != 1) {
                connection.rollback();
                throw new IllegalStateException("账号必须已存在且恰好一行，实际 " + rows + " 行（本工具不注册新账号）");
            }
            if (alreadyMigrated) {
                connection.rollback();
                throw new IllegalStateException("账号已有 Argon2id 哈希；本工具不支持改密 / 重置");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE account SET password_hash = ? WHERE account = ?")) {
                update.setQueryTimeout(TIMEOUT_SECONDS);
                update.setString(1, encoded);
                update.setString(2, account);
                int affected = update.executeUpdate();
                if (affected != 1) {
                    connection.rollback();
                    throw new IllegalStateException("写入影响了 " + affected + " 行，应恰好 1 行");
                }
            }
            connection.commit();
        }
    }

    static String readPasswordTwice(Console console) {
        if (console == null) {
            throw new IllegalStateException("标准输入不是终端；批处理请显式用 --password-stdin");
        }
        char[] first = console.readPassword("口令：");
        char[] second = console.readPassword("再输一遍：");
        if (first == null || second == null) {
            throw new IllegalStateException("没有读到口令");
        }
        try {
            if (!Arrays.equals(first, second)) {
                throw new IllegalStateException("两次口令不一致");
            }
            return new String(first);
        } finally {
            Arrays.fill(first, '\0');
            Arrays.fill(second, '\0');
        }
    }

    /** 恰好两行、相同（行尾 \r 去掉，同基线）。 */
    static String readPasswordLines(InputStream stdin) {
        List<String> lines = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == 2) {
                    throw new IllegalStateException("--password-stdin 只接受恰好两行");
                }
                if (line.length() > 4096) {
                    throw new IllegalStateException("口令行过长");
                }
                lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读标准输入失败: " + e.getClass().getSimpleName());
        }
        if (lines.size() != 2) {
            throw new IllegalStateException("--password-stdin 需要恰好两行");
        }
        if (!lines.get(0).equals(lines.get(1))) {
            throw new IllegalStateException("两次口令不一致");
        }
        return lines.get(0);
    }
}
