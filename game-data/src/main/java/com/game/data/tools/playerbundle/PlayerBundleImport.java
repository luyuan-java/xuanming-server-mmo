package com.game.data.tools.playerbundle;

import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import com.game.discovery.RedisKeys;
import com.game.discovery.namespace.GameNamespaceBinder;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 把 {@link PlayerBundleExport} 的导出包灌进<b>本地隔离环境</b>，让本地登录走 Java 版真实加载链路（login 夺权 → scene 读 {@code player} 行与
 * {@code player_state}）把这名玩家加载起来。使用手册见 {@code docs/design/player-bundle-debug.md}。
 *
 * <p>用法（缺省只预览，不连任何外部系统；确认写目标后加 {@code --execute}）：
 * <pre>
 * export GAME_NAMESPACE=game
 * export GAME_BUNDLE_IMPORT_JDBC_URL='jdbc:mysql://127.0.0.1:3306/game?user=root&amp;password='
 * java -cp game-data/target/game-data-0.1.0-SNAPSHOT.jar -Dloader.main=com.game.data.tools.playerbundle.PlayerBundleImport \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --file &lt;导出包&gt; --local-account dev_tester \
 *      [--zone &lt;归属区&gt;] [--name &lt;新名字&gt;] [--replace] [--execute] [--allow-target-host &lt;本地服务名&gt;] \
 *      [--redis redis://127.0.0.1:6379] [--redis-database 12] [--expect-database &lt;库名&gt;]
 * </pre>
 *
 * <p>写前闸门（任一不过都不写，退出 2）：① 库与 Redis 的地址必须是本机回环（本地 docker 服务名用 {@code --allow-target-host} 显式放行）；
 * ② 库名等于部署命名空间；③ 目标实例身份取得到、且不是导出来源（{@link InstanceIdentity}）；④ 本地同号玩家不在线、已有存档要 {@code --replace}、
 * 名字不与本地别的玩家冲突（{@link LocalPlayerImporter#admit}）。
 *
 * <p>写入分三段（{@link LocalPlayerImporter}）：① 一个事务占位并写入（新 epoch、未释放、永不过期的占位租约 + 玩法数据）；② 删本地同号玩家残留的
 * 位置记录、战斗锁与待结算奖励；③ 确认占位仍是自己的再置为已释放。①之后任一步中断，玩家停在「登录回 2005」，带 {@code --replace} 重跑即可。
 * 退出码 0 成功（或预览）、1 执行中失败、2 参数 / 包 / 闸门不通过。
 */
public final class PlayerBundleImport {

    static final String JDBC_URL_ENV = "GAME_BUNDLE_IMPORT_JDBC_URL";
    static final String REDIS_PASSWORD_ENV = "GAME_BUNDLE_IMPORT_REDIS_PASSWORD";
    static final String EXPECT_DATABASE_HINT = "--expect-database <库名>";
    private static final int TIMEOUT_SECONDS = 10;

    record Args(Path file, String localAccount, Long zone, String name, boolean replace, boolean execute, Set<String> allowHosts,
                String redis, int redisDatabase, String expectDatabase) {
    }

    private PlayerBundleImport() {
    }

    public static void main(String[] args) {
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        System.exit(run(Arrays.asList(args), System.getenv(), System.getProperties(), System.out, System.err,
                PlayerBundleExport.REAL, System::currentTimeMillis));
    }

    static int run(List<String> argv, Map<String, String> env, Map<?, ?> systemProperties, PrintStream out, PrintStream err,
                   PlayerBundleExport.Connections connections, LongSupplier clock) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println("用法：--file <导出包> --local-account <本地账号> [--zone <归属区>] [--name <新名字>] [--replace] [--execute]"
                    + " [--allow-target-host <本地服务名>] [--redis <地址>] [--redis-database <库号>] [--expect-database <库名>]");
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
        PlayerBundle bundle;
        ImportPlan plan;
        try {
            bundle = PlayerBundle.json().readValue(Files.readAllBytes(args.file()), PlayerBundle.class);
            plan = ImportPlan.from(bundle, args.localAccount(), args.zone(), args.name());
        } catch (IOException e) {
            err.println("读导出包失败：" + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            err.println("导出包不能导入：" + e.getMessage());
            return 2;
        } catch (NullPointerException e) {
            err.println("导出包不能导入：缺必需字段（包被截断或改过）");
            return 2;
        }
        String url = env.get(JDBC_URL_ENV);
        if (url == null || !url.startsWith("jdbc:")) {
            err.println("环境变量 " + JDBC_URL_ENV + " 未设置或不是 jdbc: 开头的 URL");
            return 2;
        }
        RedisKeys keys = PlayerBundleExport.redisKeys(namespace);
        List<String> cleanup = PlayerRedisKeys.importCleanup(keys, plan.playerId());
        printPlan(out, bundle, plan, url, args, cleanup);

        // 闸门一：写目标必须是本机（按配置判，在建任何连接之前）
        for (String problem : new String[] {LocalTargets.checkJdbcUrl(url, args.allowHosts()),
                LocalTargets.checkRedisAddress(args.redis(), args.allowHosts())}) {
            if (problem != null) {
                err.println(problem);
                return 2;
            }
        }
        if (!args.execute()) {
            out.println();
            out.println("[预览] 没有连接、没有写入。确认上面的写目标都是本地隔离环境后加 --execute 执行。");
            return 0;
        }

        long now = clock.getAsLong();
        String exportedMysql = bundle.sources() == null ? null : bundle.sources().mysqlIdentity();
        String exportedRedis = bundle.sources() == null ? null : bundle.sources().redisIdentity();
        try (Connection conn = connections.mysql(url);
             PlayerRedis redis = connections.redis(args.redis(), args.redisDatabase(), env.get(REDIS_PASSWORD_ENV))) {
            String mismatch = NamespaceDatabaseCheck.verify(conn, args.expectDatabase(), namespace, EXPECT_DATABASE_HINT);
            if (mismatch != null) {
                err.println(mismatch);
                return 2;
            }
            // 闸门二：目标实例不能就是导出来源（port-forward 之后线上实例也在 127.0.0.1，只看地址拦不住）
            String mysqlProblem = InstanceIdentity.checkNotExportSource("MySQL", connections.mysqlIdentity(conn), exportedMysql);
            String redisProblem = InstanceIdentity.checkNotExportSource("Redis", InstanceIdentity.redis(redis.infoServer()), exportedRedis);
            for (String problem : new String[] {mysqlProblem, redisProblem}) {
                if (problem != null) {
                    err.println(problem);
                    return 2;
                }
            }
            LocalPlayerImporter importer = new LocalPlayerImporter(conn);
            LocalPlayerImporter.LocalState local = importer.inspect(plan.playerId());
            out.println("本地同号玩家：" + (local.exists() ? "有（账号 " + local.account() + "，epoch " + Long.toUnsignedString(local.ownerEpoch())
                    + (local.importHold() ? "，上次导入中断的占位" : "") + "）" : "无"));

            long epoch;
            try {
                epoch = importer.stage(plan, args.replace(), now);
            } catch (LocalPlayerImporter.Refused e) {
                err.println("拒绝导入：" + e.getMessage());
                return 2;
            } catch (SQLException e) {
                err.println("第 1 步（占位并写入）失败，事务已回滚、没有写入，可直接重跑：数据库错误 SQLState=" + e.getSQLState()
                        + " code=" + e.getErrorCode());
                return 1;
            }
            out.println("  [OK] 第 1 步：玩家行与玩法数据已写入，归属占位 epoch " + Long.toUnsignedString(epoch) + "（此刻登录会回 2005）");
            try {
                long deleted = redis.delete(cleanup);
                out.println("  [OK] 第 2 步：本地 Redis 删掉 " + deleted + " 把残留键");
            } catch (RuntimeException e) {
                err.println("第 2 步（清本地 Redis 残留键）失败：库里已写入并占位，玩家此刻进不来；修好 Redis 后加 --replace 重跑。原因 "
                        + e.getClass().getSimpleName());
                return 1;
            }
            boolean published;
            try {
                published = importer.publish(plan.playerId(), epoch, clock.getAsLong());
            } catch (SQLException e) {
                err.println("第 3 步（发布）失败：玩家仍处于导入占位、进不来，加 --replace 重跑。数据库错误 SQLState=" + e.getSQLState()
                        + " code=" + e.getErrorCode());
                return 1;
            }
            if (!published) {
                err.println("第 3 步（发布）没有生效：占位已被别的导入或人工操作改动；查明后加 --replace 重跑");
                return 1;
            }
            out.println("  [OK] 第 3 步：归属已释放，玩家可以登录");
        } catch (SQLException e) {
            err.println("数据库错误 SQLState=" + e.getSQLState() + " code=" + e.getErrorCode());
            return 1;
        } catch (RuntimeException e) {
            err.println("连接或读取失败：" + e.getClass().getSimpleName() + "，没有写入");
            return 1;
        }
        out.println();
        out.println("[OK] 玩家 " + Long.toUnsignedString(plan.playerId()) + " 已导入本地。下一步：用账号 " + plan.account()
                + " 登录（48），再 EnterGame（26）这个玩家号；本地区服目录里要有 zone " + plan.zoneId() + " 且处于开放状态");
        return 0;
    }

    private static void printPlan(PrintStream out, PlayerBundle bundle, ImportPlan plan, String url, Args args, List<String> cleanup) {
        out.println("=== 导入预览（" + PlayerBundle.FORMAT + " v" + bundle.formatVersion() + "）===");
        out.println("  玩家：      " + Long.toUnsignedString(plan.playerId()) + "（名字 " + plan.name() + "，归属区 " + plan.zoneId() + "）");
        out.println("  导出：      " + bundle.exportedAtUtc() + " 由 " + bundle.operator() + "，原因 " + bundle.reason());
        if (bundle.serverRevision() != null) {
            out.println("  线上版本：  " + bundle.serverRevision() + "（本地检出须与之一致）");
        }
        if (bundle.analysis() != null) {
            out.println("  线上判读：  " + bundle.analysis().ownership() + "；" + bundle.analysis().persistence());
        }
        out.println("  玩法数据：  " + (plan.stateBytes().length + " 字节"));
        out.println("  本地账号：  " + plan.account());
        out.println("  写目标：    库 " + LocalTargets.describeJdbc(url) + "；Redis " + LocalTargets.describeRedis(args.redis()) + " DB "
                + args.redisDatabase() + "（只删 " + String.join("、", cleanup) + "）");
        out.println("  覆盖已有：  " + (args.replace() ? "是（--replace）" : "否"));
        plan.notes().forEach(note -> out.println("  注意：" + note));
    }

    static Args parse(List<String> argv) {
        Path file = null;
        String localAccount = null;
        Long zone = null;
        String name = null;
        boolean replace = false;
        boolean execute = false;
        Set<String> allowHosts = new LinkedHashSet<>();
        String redis = "redis://127.0.0.1:6379";
        int redisDatabase = 12;
        String expectDatabase = null;
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            switch (arg) {
                case "--replace" -> replace = true;
                case "--execute" -> execute = true;
                case "--file", "--local-account", "--zone", "--name", "--allow-target-host", "--redis", "--redis-database",
                     "--expect-database" -> {
                    if (i + 1 >= argv.size()) {
                        throw new IllegalArgumentException("参数缺值：" + arg);
                    }
                    String value = argv.get(++i);
                    switch (arg) {
                        case "--file" -> file = Path.of(value);
                        case "--local-account" -> localAccount = PlayerBundleExport.nonBlank(arg, value);
                        case "--zone" -> zone = (long) PlayerBundleExport.parseInt(arg, value, 1, Integer.MAX_VALUE);
                        case "--name" -> name = PlayerBundleExport.nonBlank(arg, value);
                        case "--allow-target-host" -> allowHosts.add(PlayerBundleExport.nonBlank(arg, value));
                        case "--redis" -> redis = value;
                        case "--redis-database" -> redisDatabase = PlayerBundleExport.parseInt(arg, value, 0, 15);
                        default -> expectDatabase = PlayerBundleExport.nonBlank(arg, value);
                    }
                }
                default -> throw new IllegalArgumentException("未知参数：" + arg);
            }
        }
        if (file == null || localAccount == null) {
            throw new IllegalArgumentException("--file 与 --local-account 都必须给");
        }
        return new Args(file, localAccount, zone, name, replace, execute, Set.copyOf(allowHosts), redis, redisDatabase, expectDatabase);
    }
}
