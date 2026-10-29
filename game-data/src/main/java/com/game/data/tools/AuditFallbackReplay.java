package com.game.data.tools;

import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsTables;
import com.game.data.ops.pb.AuditReplayLineRow;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.namespace.GameNamespaceBinder;
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
 * 审计兜底日志回灌工具（data-ops-spec §2.4、Q16；PARITY 资产流水行的「Java 待做」）：把 game-scene 写进 {@code game.audit.fallback}
 * 的资产流水行补进 {@code transaction_log}。核心见 {@link FallbackReplayer}；快照兜底行只有元数据，不回灌（有意如此）。
 *
 * <p>用法（在仓库根目录，game-data 已打包；只回灌<b>已轮转、不再写入</b>的日志文件）：
 * <pre>
 * export GAME_NAMESPACE=game        # 部署命名空间，必须与服务进程同值（缺省 game；也可以用 -Dgame.namespace=game）
 * export GAME_AUDIT_REPLAY_JDBC_URL='jdbc:mysql://127.0.0.1:3306/&lt;库名 = 部署命名空间&gt;?user=...&amp;password=...'
 * java -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar -Dloader.main=com.game.data.tools.AuditFallbackReplay \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --file &lt;兜底日志文件&gt; [--dry-run] \
 *      [--redis redis://127.0.0.1:6379] [--redis-database 12] [--expect-database &lt;库名&gt;]
 * </pre>
 * JDBC URL（含库名与账号口令）只从环境变量读（{@code --jdbc-url-env} 可换变量名）；Redis 口令取环境变量 {@code GAME_AUDIT_REPLAY_REDIS_PASSWORD}（可不设）。
 * 部署命名空间（Redis 键前缀，见 {@link GameNamespace}）与服务进程同口径绑定（{@link GameNamespaceBinder}：同一个属性类型、同一套宽松绑定，
 * {@code -Dgame.namespace} 优先于环境变量 {@code GAME_NAMESPACE}；没配取 game，设了空串照样拒绝）；取到后连同来源打印，再自己构造 {@link RedisKeys}。
 * 连上库后、建运维表之前核对 {@code SELECT DATABASE()} 等于期望库名（规格：库名 = 命名空间；{@code --expect-database} 只是给库名偏离规格的存量部署的
 * 应急开关），不一致退出 2。
 * 文件里有 {@code tx_id=0} 的行时才连 Redis：在 {@code NodeTypes.SCENE_GUID} 全服池占一个 worker 发新号（与 scene / game-data 同池，不撞号），
 * 结束时交还。{@code --dry-run} 只解析、报告计数，不连库（也不做库名核对）。
 * 退出码 0 成功、1 失败（含有解析不了的行、发号中止、库错误）、2 参数错误 / 命名空间非法 / 库名核对不过。
 */
public final class AuditFallbackReplay {

    static final String DEFAULT_URL_ENV = "GAME_AUDIT_REPLAY_JDBC_URL";
    static final String REDIS_PASSWORD_ENV = "GAME_AUDIT_REPLAY_REDIS_PASSWORD";
    /** 库名核对失败时报错里给出的应急开关写法。 */
    static final String EXPECT_DATABASE_HINT = "--expect-database <库名>";
    private static final int TIMEOUT_SECONDS = 10;

    /**
     * 解析后的参数。
     *
     * @param expectDatabase 期望的库名（{@code --expect-database}）；null = 取部署命名空间
     */
    record Args(Path file, boolean dryRun, String urlEnv, String redis, int redisDatabase, String expectDatabase) {
    }

    /** 外部连接接缝：同步打开连接，由run负责关闭；默认仍使用正式NORMAL工厂。 */
    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url, Map<String, String> env) throws SQLException;
    }

    private AuditFallbackReplay() {
    }

    public static void main(String[] args) {
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        System.exit(run(Arrays.asList(args), System.getenv(), System.getProperties(), System.out, System.err));
    }

    /** 没有系统属性的 {@link #run(List, Map, Map, PrintStream, PrintStream)}（测试用）。 */
    static int run(List<String> argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return run(argv, env, Map.of(), out, err);
    }

    /**
     * @param env              环境变量（JDBC URL、Redis 口令、部署命名空间）
     * @param systemProperties 系统属性（部署命名空间 {@code -Dgame.namespace}）
     */
    static int run(List<String> argv, Map<String, String> env, Map<?, ?> systemProperties, PrintStream out, PrintStream err) {
        return run(argv, env, systemProperties, out, err,
                (url, variables) -> com.game.common.sql.AuthorityDataSource.fromEnvironment(url, variables).getConnection());
    }

    static int run(List<String> argv, Map<String, String> env, Map<?, ?> systemProperties, PrintStream out, PrintStream err,
                   ConnectionOpener connections) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println("用法：--file <兜底日志文件> [--dry-run] [--jdbc-url-env <变量名>] [--redis <地址>] [--redis-database <库号>]"
                    + " [--expect-database <库名>]");
            err.println("部署命名空间与服务进程同口径：-D" + GameNamespace.PROPERTY + " 或环境变量 " + GameNamespace.ENV + "（前者优先，缺省 "
                    + GameNamespace.DEFAULT_VALUE + "）；库名必须等于命名空间，--expect-database 只是库名偏离规格的存量部署的应急开关");
            return 2;
        }
        // 部署命名空间：从 run() 注入的系统属性与环境变量绑定（不靠 System.getenv，可测）；非法值在连任何外部系统之前退出 2
        GameNamespace namespace;
        try {
            namespace = resolveNamespace(env, systemProperties, err);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
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
        // 不起 Spring 的入口：按取到的命名空间自己构造键的出处（只用于节点号租约）
        RedisKeys keys = new RedisKeys(namespace);
        try (Connection conn = connections.open(url, env);
             LazyIds ids = new LazyIds(keys, args.redis(), args.redisDatabase(), env.get(REDIS_PASSWORD_ENV), err)) {
            // 建运维表（OpsTables.sync）之前核对库名：命名空间与库必须属于同一个部署
            String mismatch = NamespaceDatabaseCheck.verify(conn, args.expectDatabase(), namespace, EXPECT_DATABASE_HINT);
            if (mismatch != null) {
                err.println(mismatch);
                return 2;
            }
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

    /**
     * 取部署命名空间（{@link GameNamespaceBinder}：与服务进程同一个属性类型、同一套宽松绑定，没配取 game）并连同来源打印到 err。
     *
     * @throws IllegalArgumentException 取值非法（含空串）
     */
    static GameNamespace resolveNamespace(Map<String, String> env, Map<?, ?> systemProperties, PrintStream err) {
        GameNamespaceBinder.Bound bound = GameNamespaceBinder.bind(systemProperties, env);
        err.println("部署命名空间 " + GameNamespace.PROPERTY + "=" + bound.namespace() + "（来源 " + bound.origin() + "）");
        return bound.namespace();
    }

    static Args parse(List<String> argv) {
        Path file = null;
        boolean dryRun = false;
        String urlEnv = DEFAULT_URL_ENV;
        String redis = "redis://127.0.0.1:6379";
        int database = 12;
        String expectDatabase = null;
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            switch (arg) {
                case "--dry-run" -> dryRun = true;
                case "--file", "--jdbc-url-env", "--redis", "--redis-database", "--expect-database" -> {
                    if (i + 1 >= argv.size()) {
                        throw new IllegalArgumentException("参数缺值：" + arg);
                    }
                    String value = argv.get(++i);
                    switch (arg) {
                        case "--file" -> file = Path.of(value);
                        case "--jdbc-url-env" -> urlEnv = value;
                        case "--redis" -> redis = value;
                        case "--expect-database" -> {
                            if (value.isBlank()) {
                                throw new IllegalArgumentException("--expect-database 不能为空");
                            }
                            expectDatabase = value;
                        }
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
        return new Args(file, dryRun, urlEnv, redis, database, expectDatabase);
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
        private final RedisKeys keys;
        private final String address;
        private final int database;
        private final String password;
        private final PrintStream err;
        private RedissonClient redis;
        private ScheduledExecutorService scheduler;
        private NodeIdLease lease;
        private LeaseGatedSnowflake snowflake;
        private boolean failed;

        /** @param keys 节点号租约键的出处（按本工具取到的部署命名空间构造） */
        LazyIds(RedisKeys keys, String address, int database, String password, PrintStream err) {
            this.keys = keys;
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
                    lease = NodeIdLease.acquire(redis, keys, scheduler, NodeTypes.SCENE_GUID, OpsIds.SCOPE, 1, Snowflake.MAX_WORKER,
                            "game-audit-replay-" + UUID.randomUUID(), OpsIds.LEASE_TTL,
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
            RuntimeException failure=null;
            try{if(lease!=null)lease.close();}catch(RuntimeException unknown){failure=new IllegalStateException("回灌发号租约释放未确认");}
            if(scheduler!=null){
                scheduler.shutdownNow();
                try{if(!scheduler.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))failure=new IllegalStateException("回灌发号续租线程未真实join");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();failure=new IllegalStateException("回灌续租线程join被取消");}
            }
            try{if(redis!=null)redis.shutdown(0,5,java.util.concurrent.TimeUnit.SECONDS);}catch(RuntimeException unknown){failure=new IllegalStateException("回灌Redis关闭未确认");}
            if(failure!=null)throw failure;
        }
    }
}
