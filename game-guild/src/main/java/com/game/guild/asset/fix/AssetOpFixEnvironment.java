package com.game.guild.asset.fix;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import com.game.discovery.namespace.GameNamespaceBinder;
import com.game.guild.GuildProperties;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.asset.JdbcGuildAssetStore;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.cache.InvalidationOp;
import com.game.guild.cache.RedissonGuildCacheRedis;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.store.BackgroundTx;
import com.game.guild.store.Invalidation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * assetopfix 的连接装配（基线 assetopfix/main.go:148-221 withStore / openStore）：读与服务进程<b>同一份</b>配置（缺省 jar 里的 {@code application.yaml}，
 * 环境变量 / {@code -D} 按 Spring Boot 的规则覆盖，口令同样只从环境变量来），只建 MySQL 连接池（同一条带 RC、{@code innodb_lock_wait_timeout=1}、
 * {@code useAffectedRows=true} 的 URL）与缓存 Redis（终结会改帮会资金 / 成员帮贡，必须失效缓存；连不上就<b>拒绝执行</b>，list 也一样，只为少一条分支）。
 * 不起 Spring 上下文、不起 Dubbo、不连 scene / Kafka，不推送。
 *
 * <p>已知限制（同基线）：缓存失效的<b>后台重试</b>（100 / 400 / 1600 ms）在进程退出时最多再等 4 s；首次失效是同步的，Redis 健康时它就足够。
 * 错误文本不含口令与连接串原文。
 */
final class AssetOpFixEnvironment {

    private static final Logger log = LoggerFactory.getLogger(AssetOpFixEnvironment.class);

    static final long PING_TIMEOUT_MS = 5_000L;
    /** 提交后同步失效的预算（没有请求预算可继承）。 */
    static final long INVALIDATE_BUDGET_MS = 2_000L;
    /** 库名核对报错里给出的应急开关写法。 */
    static final String EXPECT_DATABASE_HINT = "-expect-database <库名>";

    private AssetOpFixEnvironment() {
    }

    /** 生产用的打开方式（带 {@code -expect-database}）。 */
    static final AssetOpFixMain.StoreOpener OPENER = new AssetOpFixMain.StoreOpener() {
        @Override
        public AssetOpFixMain.OpenedStore open(String configPath) throws Exception {
            return AssetOpFixEnvironment.open(configPath, null);
        }

        @Override
        public AssetOpFixMain.OpenedStore open(String configPath, String expectDatabase) throws Exception {
            return AssetOpFixEnvironment.open(configPath, expectDatabase);
        }
    };

    /** 按配置连库与 Redis，给出 Store（期望库名 = 部署命名空间）。 */
    static AssetOpFixMain.OpenedStore open(String configPath) throws Exception {
        return open(configPath, null);
    }

    /**
     * 按配置连库与 Redis，给出 Store。顺序：读配置 → 解析并校验部署命名空间（{@link #resolveNamespace}，非法即前置条件不满足）→ 才读 JDBC URL
     * （库名段是 {@code ${game.namespace:game}}）连库 → 核对 {@code DATABASE()} 等于期望库名（不一致抛 {@link AssetOpFixMain.PreconditionException}，
     * 不连 Redis）→ 连 Redis，用这个命名空间构造 {@link RedisKeys} 交给缓存失效。
     *
     * @param expectDatabase 期望库名（{@code -expect-database}，应急开关）；null = 取部署命名空间（规格）
     */
    static AssetOpFixMain.OpenedStore open(String configPath, String expectDatabase) throws Exception {
        StandardEnvironment env = load(configPath);
        GameNamespace namespace = resolveNamespace(env);
        Binder binder = Binder.get(env);
        String url = env.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("配置里没有 spring.datasource.url");
        }
        GuildProperties props = binder.bind("game.guild", GuildProperties.class)
                .orElseGet(() -> new GuildProperties(null, null, null, null, null, null, null, null));
        RedisProperties redisProps = binder.bind("game.redis", RedisProperties.class)
                .orElseGet(() -> new RedisProperties(null, null, null, null, null, null, null));

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(env.getRequiredProperty("spring.datasource.username"));
        dataSource.setPassword(env.getRequiredProperty("spring.datasource.password"));
        dataSource.setInitialSize(0);
        dataSource.setMinIdle(0);
        dataSource.setMaxActive(2);
        dataSource.setMaxWait(PING_TIMEOUT_MS);
        dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        String databaseLabel = expectDatabase == null ? namespace.value() : expectDatabase;
        String mismatch;
        try{com.game.discovery.sql.AuthorityDataSourcePolicy.protectDruid(dataSource,com.game.common.sql.WriterAuthority.consumeEnvironment(),true);}
        catch(java.io.IOException invalid){dataSource.close();throw new IllegalStateException("NORMAL CLI启动grant缺失",invalid);}
        try (Connection conn = dataSource.getConnection(PING_TIMEOUT_MS)) {
            if (!conn.isValid((int) (PING_TIMEOUT_MS / 1000))) {
                throw new IllegalStateException("连接无效");
            }
            mismatch = NamespaceDatabaseCheck.verify(conn, expectDatabase, namespace, EXPECT_DATABASE_HINT);
        } catch (Exception e) {
            dataSource.close();
            // 连接串带口令：只报库名与原因类别
            throw new IllegalStateException("连接 MySQL（库 " + databaseLabel + "）失败: " + e.getClass().getSimpleName(), e);
        }
        if (mismatch != null) {
            dataSource.close();
            throw new AssetOpFixMain.PreconditionException(mismatch + "；拒绝执行（没有连 Redis，没有改任何数据）");
        }

        RedissonClient redis;
        try {
            Config config = new Config();
            config.useSingleServer().setAddress(redisProps.address()).setDatabase(redisProps.database())
                    .setPassword(redisProps.password()).setConnectTimeout(redisProps.connectTimeoutMs())
                    .setTimeout(redisProps.timeoutMs()).setRetryAttempts(redisProps.retryAttempts());
            redis = Redisson.create(config);
        } catch (RuntimeException e) {
            dataSource.close();
            throw new IllegalStateException("连接缓存 Redis " + redisProps.address() + "（DB " + redisProps.database()
                    + "）失败，拒绝执行（终结后无法失效帮会 / 成员缓存）: " + e.getClass().getSimpleName(), e);
        }
        if (!pingQuietly(redis)) {
            redis.shutdown();
            dataSource.close();
            throw new IllegalStateException("缓存 Redis " + redisProps.address() + "（DB " + redisProps.database()
                    + "）不可用，拒绝执行（终结后无法失效帮会 / 成员缓存）");
        }

        ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("assetopfix-invalidate").daemon(true).factory());
        GuildMetrics metrics = new GuildMetrics(new SimpleMeterRegistry());
        // 缓存键与服务进程同一个命名空间（上面已核对它与库属于同一个部署）
        RedisKeys redisKeys = new RedisKeys(namespace);
        GuildCacheInvalidator invalidator = new GuildCacheInvalidator(new RedissonGuildCacheRedis(redis), redisKeys, props.cacheTtl(),
                background, metrics);
        BackgroundTx tx = new BackgroundTx(dataSource::getConnection, props.queryTimeoutCapSeconds(), BackgroundTx.Listener.NONE);
        GuildAssetStore store = new JdbcGuildAssetStore(tx, new ManualListener(invalidator),
                new com.game.guild.asset.RedissonTerminalCleanupWatermark(redis, redisKeys));
        return new AssetOpFixMain.OpenedStore(store, () -> {
            // 让后台失效重试有机会跑完（一次性延迟任务在 shutdown 之后照常执行）
            background.shutdown();
            try {
                background.awaitTermination(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            background.shutdownNow();
            redis.shutdown();
            dataSource.close();
        });
    }

    /**
     * 从同一份配置解析部署命名空间：与服务进程、另外两个命令行工具同一个入口 {@link GameNamespaceBinder}（同一个绑定类型
     * {@code GameNamespaceProperties}、同口径的宽松绑定：{@code -D game.namespace}、环境变量 {@code GAME_NAMESPACE}，再到 {@code -f} 的 yaml），
     * 没配取 {@code game}；按 {@link GameNamespace} 的规则校验（显式空串照样拒绝），连同来源打到日志。
     * 非法抛 {@link AssetOpFixMain.PreconditionException}（退出 2，连库、连 Redis 之前）。
     */
    static GameNamespace resolveNamespace(StandardEnvironment env) {
        GameNamespaceBinder.Bound bound;
        try {
            bound = GameNamespaceBinder.bind(env);
        } catch (IllegalArgumentException e) {
            throw new AssetOpFixMain.PreconditionException(e.getMessage());
        }
        log.info("部署命名空间 {}={}（来源 {}）", GameNamespace.PROPERTY, bound.namespace(), bound.origin());
        return bound.namespace();
    }

    /** 读配置：{@code -f} 给的 yaml 文件，缺省 classpath 的 application.yaml；系统属性与环境变量优先（StandardEnvironment 的顺序）。 */
    static StandardEnvironment load(String configPath) throws IOException {
        Resource resource;
        if (configPath == null || configPath.isBlank()) {
            resource = new ClassPathResource("application.yaml");
        } else {
            Path path = Path.of(configPath);
            if (!Files.isRegularFile(path)) {
                throw new IOException("配置文件不存在: " + path.toAbsolutePath());
            }
            resource = new FileSystemResource(path);
        }
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("assetopfix-config", resource);
        sources.forEach(env.getPropertySources()::addLast);
        return env;
    }

    private static boolean pingQuietly(RedissonClient redis) {
        try {
            return redis.getRedisNodes(RedisNodes.SINGLE).pingAll(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("ping 缓存 Redis 失败: {}", e.toString());
            return false;
        }
    }

    /** 人工终结提交之后：只失效缓存（终结 origin = MANUAL，不推送；orphan 只记日志）。 */
    private record ManualListener(GuildCacheInvalidator invalidator) implements GuildAssetStore.Listener {

        @Override
        public void invalidate(Invalidation invalidation) {
            if (invalidation.isEmpty()) {
                return;
            }
            InvalidationOp op;
            try {
                op = InvalidationOp.ofLabel(invalidation.op().label());
            } catch (IllegalArgumentException e) {
                op = InvalidationOp.ASSET_FINALIZE;
            }
            invalidator.afterCommit(op, invalidation.guildId(), invalidation.playerIds(), Deadline.after(INVALIDATE_BUDGET_MS));
        }

        @Override
        public void orphan(GuildAssetStore.Orphan orphan) {
            log.info("[AssetOpManual] 对侧账无处可记 kind={} what={}", orphan.kind(), orphan.what());
        }

        @Override
        public void cleanupDeleted(GuildAssetStore.CleanupTable table, long n) {
        }

        @Override
        public void finalized(GuildAssetStore.FinalizedOp op) {
            // 人工终结不推送（基线 OnFinalized 为 nil）
        }
    }
}
