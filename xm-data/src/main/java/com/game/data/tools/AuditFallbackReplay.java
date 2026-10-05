package com.game.data.tools;

import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsTables;
import com.game.data.ops.pb.AuditReplayLineRow;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.pbmysql.PbMysql;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 审计兜底日志回灌工具（data-ops-spec §2.4、Q16；PARITY 资产流水行的「Java 待做」）：把 xm-scene 写进 {@code xm.audit.fallback}
 * 的资产流水行补进 {@code transaction_log}。核心见 {@link FallbackReplayer}；快照兜底行只有元数据，不回灌（有意如此）。
 *
 * <p>用法（在仓库根目录，xm-data 已打包；只回灌<b>已轮转、不再写入</b>的日志文件）：
 * <pre>
 * export XM_AUDIT_REPLAY_JDBC_URL='jdbc:mysql://127.0.0.1:3306/xm_java?user=...&amp;password=...'
 * java -cp xm-data/target/xm-data-0.1.0-SNAPSHOT.jar -Dloader.main=com.game.data.tools.AuditFallbackReplay \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --file &lt;兜底日志文件&gt; [--dry-run] \
 *      [--redis redis://127.0.0.1:6379] [--redis-database 12]
 * </pre>
 * JDBC URL（含库名与账号口令）只从环境变量读（{@code --jdbc-url-env} 可换变量名）；Redis 口令取环境变量 {@code XM_AUDIT_REPLAY_REDIS_PASSWORD}（可不设）。
 * 文件里有 {@code tx_id=0} 的行时才连 Redis：在 {@code NodeTypes.SCENE_GUID} 全服池占一个 worker 发新号（与 scene / xm-data 同池，不撞号），
 * 结束时交还。{@code --dry-run} 只解析、报告计数，不连库。退出码 0 成功、1 失败（含有解析不了的行、发号中止、库错误）、2 参数错误。
 */
public final class AuditFallbackReplay {

    static final String DEFAULT_URL_ENV = "XM_AUDIT_REPLAY_JDBC_URL";
    static final String REDIS_PASSWORD_ENV = "XM_AUDIT_REPLAY_REDIS_PASSWORD";
    private static final int TIMEOUT_SECONDS = 10;

    /** 解析后的参数。 */
    record Args(Path file, boolean dryRun, String urlEnv, String redis, int redisDatabase) {
    }

    private AuditFallbackReplay() {
    }

    public static void main(String[] args) {
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        System.exit(run(Arrays.asList(args), System.getenv(), System.out, System.err));
    }

    static int run(List<String> argv, Map<String, String> env, PrintStream out, PrintStream err) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println("用法：--file <兜底日志文件> [--dry-run] [--jdbc-url-env <变量名>] [--redis <地址>] [--redis-database <库号>]");
            return 2;
        }
        byte[] content;
        try {
            content = Files.readAllBytes(args.file());
        } catch (IOException e) {
            err.println("读文件失败：" + e.getMessage());
            return 1;
        }
        if (args.dryRun()) {
            FallbackReplayer.Stats stats = FallbackReplayer.parseOnly(content);
            print(out, args.file(), FallbackReplayer.sha256(content), stats, true);
            return stats.ok() ? 0 : 1;
        }
        String url = env.get(args.urlEnv());
        if (url == null || !url.startsWith("jdbc:")) {
            err.println("环境变量 " + args.urlEnv() + " 未设置或不是 jdbc: 开头的 URL");
            return 1;
        }
        try (Connection conn = DriverManager.getConnection(url);
             LazyIds ids = new LazyIds(args.redis(), args.redisDatabase(), env.get(REDIS_PASSWORD_ENV), err)) {
            PbMysql db = new PbMysql();
            db.register(AuditReplayLineRow.getDefaultInstance());
            OpsTables.sync(db, conn, Duration.ofMinutes(2));
            FallbackReplayer.Stats stats = new FallbackReplayer(conn, db, ids::next, System::currentTimeMillis)
                    .replay(content);
            print(out, args.file(), FallbackReplayer.sha256(content), stats, false);
            return stats.ok() ? 0 : 1;
        } catch (SQLException e) {
            // 只打 SQLState / 错误码：驱动的消息可能原样带 URL 与库口令（同 PasswordAdmin）
            err.println("数据库错误 SQLState=" + e.getSQLState() + " code=" + e.getErrorCode());
            return 1;
        } catch (RuntimeException e) {
            err.println("回灌失败：" + e);
            return 1;
        }
    }

    static Args parse(List<String> argv) {
        Path file = null;
        boolean dryRun = false;
        String urlEnv = DEFAULT_URL_ENV;
        String redis = "redis://127.0.0.1:6379";
        int database = 12;
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            switch (arg) {
                case "--dry-run" -> dryRun = true;
                case "--file", "--jdbc-url-env", "--redis", "--redis-database" -> {
                    if (i + 1 >= argv.size()) {
                        throw new IllegalArgumentException("参数缺值：" + arg);
                    }
                    String value = argv.get(++i);
                    switch (arg) {
                        case "--file" -> file = Path.of(value);
                        case "--jdbc-url-env" -> urlEnv = value;
                        case "--redis" -> redis = value;
                        default -> {
                            try {
                                database = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("--redis-database 必须是整数");
                            }
                        }
                    }
                }
                default -> throw new IllegalArgumentException("未知参数：" + arg);
            }
        }
        if (file == null) {
            throw new IllegalArgumentException("缺少 --file");
        }
        return new Args(file, dryRun, urlEnv, redis, database);
    }

    private static void print(PrintStream out, Path file, String sha, FallbackReplayer.Stats s, boolean dryRun) {
        out.println((dryRun ? "[dry-run] " : "") + "file=" + file + " sha256=" + sha);
        out.println("lines=" + s.lines() + " transactions=" + s.transactions() + " snapshotsSkipped=" + s.snapshotsSkipped()
                + " malformed=" + s.malformed() + " inserted=" + s.inserted() + " duplicate=" + s.duplicate()
                + " alreadyReplayed=" + s.alreadyReplayed() + " newIds=" + s.newIds() + " aborted=" + s.aborted());
        s.problems().forEach(p -> out.println("  " + p));
    }

    /** 第一次需要新号时才连 Redis、占 SCENE_GUID worker；关闭时交还。 */
    private static final class LazyIds implements AutoCloseable {
        private final String address;
        private final int database;
        private final String password;
        private final PrintStream err;
        private RedissonClient redis;
        private ScheduledExecutorService scheduler;
        private NodeIdLease lease;
        private LeaseGatedSnowflake snowflake;
        private boolean failed;

        LazyIds(String address, int database, String password, PrintStream err) {
            this.address = address;
            this.database = database;
            this.password = password;
            this.err = err;
        }

        OptionalLong next() {
            if (snowflake == null && !failed) {
                try {
                    Config config = new Config();
                    config.useSingleServer().setAddress(address).setDatabase(database)
                            .setPassword(password == null || password.isEmpty() ? null : password);
                    redis = Redisson.create(config);
                    scheduler = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().name("audit-replay-lease").daemon(true).factory());
                    lease = NodeIdLease.acquire(redis, scheduler, NodeTypes.SCENE_GUID, OpsIds.SCOPE, 1, Snowflake.MAX_WORKER,
                            "xm-audit-replay-" + UUID.randomUUID(), OpsIds.LEASE_TTL,
                            () -> err.println("全服发号租约丢失：停止发号"));
                    snowflake = new LeaseGatedSnowflake(new Snowflake(lease.nodeId()), lease::isValid);
                } catch (RuntimeException e) {
                    failed = true;
                    err.println("占全服发号租约失败：" + e);
                    return OptionalLong.empty();
                }
            }
            return snowflake == null ? OptionalLong.empty() : snowflake.tryNext();
        }

        @Override
        public void close() {
            if (lease != null) {
                lease.close();
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (redis != null) {
                redis.shutdown();
            }
        }
    }
}
