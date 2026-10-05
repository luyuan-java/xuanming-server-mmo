package com.game.guild.asset.fix;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisProperties;
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

    private AssetOpFixEnvironment() {
    }

    /** 按配置连库与 Redis，给出 Store。 */
    static AssetOpFixMain.OpenedStore open(String configPath) throws Exception {
        StandardEnvironment env = load(configPath);
        Binder binder = Binder.get(env);
        String url = env.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("配置里没有 spring.datasource.url");
        }
        GuildProperties props = binder.bind("xm.guild", GuildProperties.class)
                .orElseGet(() -> new GuildProperties(null, null, null, null, null, null, null, null));
        RedisProperties redisProps = binder.bind("xm.redis", RedisProperties.class)
                .orElseGet(() -> new RedisProperties(null, null, null, null, null, null, null));

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(env.getProperty("spring.datasource.username", "root"));
        dataSource.setPassword(env.getProperty("spring.datasource.password", ""));
        dataSource.setInitialSize(0);
        dataSource.setMinIdle(0);
        dataSource.setMaxActive(2);
        dataSource.setMaxWait(PING_TIMEOUT_MS);
        dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (Connection conn = dataSource.getConnection(PING_TIMEOUT_MS)) {
            if (!conn.isValid((int) (PING_TIMEOUT_MS / 1000))) {
                throw new IllegalStateException("连接无效");
            }
        } catch (Exception e) {
            dataSource.close();
            // 连接串带口令：只报库名与原因类别
            throw new IllegalStateException("连接 MySQL（库 xm_java）失败: " + e.getClass().getSimpleName(), e);
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
        GuildCacheInvalidator invalidator = new GuildCacheInvalidator(new RedissonGuildCacheRedis(redis), props.cacheTtl(),
                background, metrics);
        BackgroundTx tx = new BackgroundTx(dataSource::getConnection, props.queryTimeoutCapSeconds(), BackgroundTx.Listener.NONE);
        GuildAssetStore store = new JdbcGuildAssetStore(tx, new ManualListener(invalidator));
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
