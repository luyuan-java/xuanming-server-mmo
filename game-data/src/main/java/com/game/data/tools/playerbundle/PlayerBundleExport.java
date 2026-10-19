package com.game.data.tools.playerbundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import com.game.discovery.RedisKeys;
import com.game.discovery.namespace.GameNamespaceBinder;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 单玩家排障导出（只读）：把一名玩家在库里与 Redis 里的相关数据导成一份 JSON（{@link PlayerBundle}），带判读、来源实例身份、操作人与原因。
 * 使用手册见 {@code docs/design/player-bundle-debug.md}。
 *
 * <p>用法（在仓库根目录，game-data 已打包）：
 * <pre>
 * export GAME_NAMESPACE=game                                   # 与服务进程同值（或 -Dgame.namespace=game）
 * export GAME_BUNDLE_EXPORT_JDBC_URL='jdbc:mysql://127.0.0.1:14000/game?user=...&amp;password=...'
 * export GAME_BUNDLE_EXPORT_REDIS_PASSWORD=...                 # 没有口令可不设
 * java -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar -Dloader.main=com.game.data.tools.playerbundle.PlayerBundleExport \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --player &lt;玩家号&gt; --operator &lt;操作人&gt; --reason &lt;工单号&gt; \
 *      --out &lt;仓库外的受控目录&gt;/player-&lt;玩家号&gt;.json [--server-revision &lt;线上提交&gt;] [--limit 20] \
 *      [--redis redis://127.0.0.1:6379] [--redis-database 12] [--skip-redis] [--expect-database &lt;库名&gt;]
 * </pre>
 *
 * <p>只读保证：库只在一个只读、可重复读事务里发固定 SELECT，结束回滚；{@code account.password_hash} 只判是否为空、原值不出库；
 * Redis 每把键一条只读脚本（TYPE / PTTL / GET / HGETALL / ZRANGE），不 SCAN，不读任何令牌键。
 * 导出文件不覆盖已有文件、不许落在 git 工作区里（防误提交），POSIX 系统上以 0600 创建。
 * 退出码 0 成功、1 失败（连不上、玩家不存在、写文件失败）、2 参数错误 / 命名空间非法 / 库名核对不过 / 输出位置不合规。
 */
public final class PlayerBundleExport {

    static final String JDBC_URL_ENV = "GAME_BUNDLE_EXPORT_JDBC_URL";
    static final String REDIS_PASSWORD_ENV = "GAME_BUNDLE_EXPORT_REDIS_PASSWORD";
    static final String EXPECT_DATABASE_HINT = "--expect-database <库名>";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 500;
    private static final int TIMEOUT_SECONDS = 10;

    /** 解析后的参数。{@code expectDatabase} 为 null = 取部署命名空间。 */
    record Args(long playerId, String operator, String reason, Path out, String serverRevision, int limit, String redis,
                int redisDatabase, boolean skipRedis, String expectDatabase) {
    }

    /** 外部连接的出处（测试注入 H2 与 Redis 替身）。 */
    interface Connections {
        Connection mysql(String url) throws SQLException;

        PlayerRedis redis(String address, int database, String password);

        /** 库的实例身份（H2 没有 {@code @@server_uuid}，测试替换它）。 */
        default String mysqlIdentity(Connection connection) {
            return InstanceIdentity.mysql(connection);
        }
    }

    static final Connections REAL = new Connections() {
        @Override
        public Connection mysql(String url) throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public PlayerRedis redis(String address, int database, String password) {
            return RedissonPlayerRedis.connect(address, database, password);
        }
    };

    private PlayerBundleExport() {
    }

    public static void main(String[] args) {
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        System.exit(run(Arrays.asList(args), System.getenv(), System.getProperties(), System.out, System.err, REAL,
                System::currentTimeMillis));
    }

    static int run(List<String> argv, Map<String, String> env, Map<?, ?> systemProperties, PrintStream out, PrintStream err,
                   Connections connections, LongSupplier clock) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println("用法：--player <玩家号> --operator <操作人> --reason <原因或工单号> --out <文件> [--server-revision <线上提交>]"
                    + " [--limit <条数>] [--redis <地址>] [--redis-database <库号>] [--skip-redis] [--expect-database <库名>]");
            err.println("库连接串只从环境变量 " + JDBC_URL_ENV + " 读；Redis 口令取 " + REDIS_PASSWORD_ENV + "（可不设）");
            return 2;
        }
        GameNamespace namespace;
        try {
            GameNamespaceBinder.Bound bound = GameNamespaceBinder.bind(systemProperties, env);
            err.println("部署命名空间 " + GameNamespace.PROPERTY + "=" + bound.namespace() + "（来源 " + bound.origin() + "）");
            namespace = bound.namespace();
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        String placement = checkOutputPath(args.out());
        if (placement != null) {
            err.println(placement);
            return 2;
        }
        if (args.serverRevision() == null) {
            err.println("提示：没给 --server-revision。玩法数据是用本地检出的 proto 解码的，与线上版本不一致时 decoded 段可能不准（原字节照样导出）");
        }
        String url = env.get(JDBC_URL_ENV);
        if (url == null || !url.startsWith("jdbc:")) {
            err.println("环境变量 " + JDBC_URL_ENV + " 未设置或不是 jdbc: 开头的 URL");
            return 1;
        }
        long now = clock.getAsLong();
        ObjectMapper json = PlayerBundle.json();
        RedisKeys keys = redisKeys(namespace);

        PlayerBundle.Mysql mysql;
        String mysqlVersion;
        String mysqlIdentity;
        String database;
        try (Connection conn = connections.mysql(url)) {
            String mismatch = NamespaceDatabaseCheck.verify(conn, args.expectDatabase(), namespace, EXPECT_DATABASE_HINT);
            if (mismatch != null) {
                err.println(mismatch);
                return 2;
            }
            database = NamespaceDatabaseCheck.currentDatabase(conn);
            mysqlVersion = version(conn);
            mysqlIdentity = connections.mysqlIdentity(conn);
            BundleMysqlReader.beginSnapshot(conn);
            try {
                mysql = new BundleMysqlReader(conn, json).read(args.playerId(), args.limit());
            } finally {
                BundleMysqlReader.endSnapshot(conn);
            }
        } catch (SQLException e) {
            // 只打 SQLState / 错误码：驱动的消息可能原样带 URL 与口令
            err.println("数据库错误 SQLState=" + e.getSQLState() + " code=" + e.getErrorCode());
            return 1;
        }
        if (mysql.player() == null) {
            err.println("库里没有玩家 " + Long.toUnsignedString(args.playerId()) + "（核对玩家号与命名空间）；不写导出文件");
            return 1;
        }
        if (mysqlIdentity == null) {
            err.println("警告：取不到库的实例身份，导入端会拒绝用这份包写本地（无法证明目标不是导出来源）");
        }

        List<PlayerBundle.RedisEntry> redisEntries = null;
        Map<String, PlayerRedis.RawEntry> rawByKey = new HashMap<>();
        String redisIdentity = null;
        if (!args.skipRedis()) {
            List<PlayerRedisKeys.Spec> specs = PlayerRedisKeys.specs(keys, args.playerId());
            try (PlayerRedis redis = connections.redis(args.redis(), args.redisDatabase(), env.get(REDIS_PASSWORD_ENV))) {
                redisIdentity = InstanceIdentity.redis(redis.infoServer());
                List<PlayerRedis.RawEntry> raw = redis.read(specs.stream().map(PlayerRedisKeys.Spec::key).toList());
                redisEntries = new ArrayList<>();
                for (int i = 0; i < specs.size(); i++) {
                    rawByKey.put(specs.get(i).key(), raw.get(i));
                    redisEntries.add(PlayerRedisKeys.render(specs.get(i), raw.get(i)));
                }
            } catch (RuntimeException e) {
                err.println("Redis 读取失败：" + e.getClass().getSimpleName() + "；可加 --skip-redis 只导库（这样的包不能导入本地）");
                return 1;
            }
            if (redisIdentity == null) {
                err.println("警告：取不到 Redis 的实例身份（INFO server 没有 run_id），导入端会拒绝用这份包写本地");
            }
        }
        List<String> readNotes = new ArrayList<>();
        if (args.skipRedis()) {
            readNotes.add("没读 Redis（--skip-redis）：在线判读只依据库里的归属列");
        }
        if (mysql.errors() != null) {
            mysql.errors().forEach((part, error) -> readNotes.add("库的 " + part + " 部分读取失败：" + error));
        }
        PlayerBundle.Analysis analysis = BundleAnalyzer.analyze(mysql.player(), mysql.playerState(), mysql.transactionLogCounts(),
                rawByKey.get(keys.playerLocation(args.playerId())), rawByKey.get(keys.battleLock(args.playerId())),
                rawByKey.get(keys.battleSettlements(args.playerId())), now, readNotes);

        PlayerBundle.Sources sources = new PlayerBundle.Sources(LocalTargets.describeJdbc(url), database, mysqlVersion, mysqlIdentity,
                args.skipRedis() ? null : LocalTargets.describeRedis(args.redis()), args.skipRedis() ? null : args.redisDatabase(),
                redisIdentity);
        PlayerBundle bundle = new PlayerBundle(PlayerBundle.FORMAT, PlayerBundle.FORMAT_VERSION, Long.toUnsignedString(args.playerId()),
                now, Instant.ofEpochMilli(now).toString(), args.operator(), args.reason(), args.serverRevision(), namespace.value(),
                sources, mysql, redisEntries, analysis);
        byte[] content;
        try {
            content = json.writeValueAsBytes(bundle);
            writeOwnerOnly(args.out(), content);
        } catch (IOException e) {
            err.println("写导出文件失败：" + e.getMessage());
            return 1;
        }
        out.println("已导出 " + args.out() + "（" + content.length + " 字节，sha256 " + BundleMysqlReader.sha256(content) + "）");
        out.println("归属：" + analysis.ownership());
        out.println("在线：" + analysis.online());
        out.println("存盘：" + analysis.persistence());
        analysis.warnings().forEach(w -> out.println("注意：" + w));
        out.println("导出包含玩家数据与账号名：放在受控目录，按工单留存，结案删除；不要提交、不要上传");
        return 0;
    }

    /**
     * 不起 Spring 的入口按取到的命名空间自己构造键的出处（导出与导入共用这一处，NamespaceLiteralGuardTest 的构造白名单只登记本文件）。
     */
    static RedisKeys redisKeys(GameNamespace namespace) {
        return new RedisKeys(namespace);
    }

    static Args parse(List<String> argv) {
        Long player = null;
        String operator = null;
        String reason = null;
        Path out = null;
        String serverRevision = null;
        int limit = DEFAULT_LIMIT;
        String redis = "redis://127.0.0.1:6379";
        int redisDatabase = 12;
        boolean skipRedis = false;
        String expectDatabase = null;
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (arg.equals("--skip-redis")) {
                skipRedis = true;
                continue;
            }
            if (!List.of("--player", "--operator", "--reason", "--out", "--server-revision", "--limit", "--redis", "--redis-database",
                    "--expect-database").contains(arg)) {
                throw new IllegalArgumentException("未知参数：" + arg);
            }
            if (i + 1 >= argv.size()) {
                throw new IllegalArgumentException("参数缺值：" + arg);
            }
            String value = argv.get(++i);
            switch (arg) {
                case "--player" -> player = parsePlayerId(value);
                case "--operator" -> operator = nonBlank(arg, value);
                case "--reason" -> reason = nonBlank(arg, value);
                case "--out" -> out = Path.of(value);
                case "--server-revision" -> serverRevision = nonBlank(arg, value);
                case "--limit" -> limit = parseInt(arg, value, 1, MAX_LIMIT);
                case "--redis" -> redis = value;
                case "--redis-database" -> redisDatabase = parseInt(arg, value, 0, 15);
                default -> expectDatabase = nonBlank(arg, value);
            }
        }
        if (player == null || operator == null || reason == null || out == null) {
            throw new IllegalArgumentException("--player、--operator、--reason、--out 都必须给");
        }
        return new Args(player, operator, reason, out, serverRevision, limit, redis, redisDatabase, skipRedis, expectDatabase);
    }

    /** 玩家号：无符号十进制、非零。 */
    static long parsePlayerId(String value) {
        long id;
        try {
            id = Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("玩家号必须是无符号十进制整数：" + value);
        }
        if (id == 0) {
            throw new IllegalArgumentException("玩家号不能为 0");
        }
        return id;
    }

    static String nonBlank(String arg, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(arg + " 不能为空");
        }
        return value.strip();
    }

    static int parseInt(String arg, String value, int min, int max) {
        try {
            int n = Integer.parseInt(value);
            if (n < min || n > max) {
                throw new IllegalArgumentException(arg + " 取值范围 " + min + "–" + max);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(arg + " 必须是整数");
        }
    }

    /**
     * 输出位置核对：文件不能已存在（不覆盖别的工单的包）、所在目录必须存在、不能在 git 工作区里（导出包含玩家数据，防误提交）。
     * 通过返回 null。
     */
    static String checkOutputPath(Path out) {
        Path file = out.toAbsolutePath().normalize();
        if (Files.exists(file)) {
            return "输出文件已存在，不覆盖：" + file;
        }
        Path dir = file.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return "输出目录不存在：" + dir;
        }
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return "输出位置在 git 工作区 " + p + " 里：导出包含玩家数据，放到仓库外的受控目录";
            }
        }
        return null;
    }

    /** 先写同目录的临时文件（POSIX 上 0600）再原子改名，不覆盖已有文件；中途失败删掉临时文件。 */
    static void writeOwnerOnly(Path out, byte[] content) throws IOException {
        Path file = out.toAbsolutePath().normalize();
        Path tmp;
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            tmp = Files.createTempFile(file.getParent(), ".bundle-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            tmp = Files.createTempFile(file.getParent(), ".bundle-", ".tmp");
        }
        try {
            Files.write(tmp, content);
            if (Files.exists(file)) {
                throw new FileAlreadyExistsException(file.toString());
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String version(Connection conn) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }
}
