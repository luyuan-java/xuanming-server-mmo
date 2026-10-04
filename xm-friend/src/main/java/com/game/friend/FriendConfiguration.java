package com.game.friend;

import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.friend.cache.FriendCache;
import com.game.friend.cache.RedissonFriendCacheRedis;
import com.game.friend.directory.OnlineDirectory;
import com.game.friend.directory.RedissonDirectoryRedis;
import com.game.friend.dispatch.FriendDispatcher;
import com.game.friend.dispatch.FriendWorkerPool;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.profile.PlayerProfiles;
import com.game.friend.quota.DirectoryQuota;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.service.FriendService;
import com.game.friend.service.RecommendService;
import com.game.friend.store.FriendStore;
import com.game.friend.store.JdbcFriendStore;
import com.game.friend.store.RecommendPivot;
import com.game.friend.store.RecommendStore;
import com.game.friend.store.SweepStore;
import com.game.friend.store.pb.FriendBlockRow;
import com.game.friend.store.pb.FriendCapacityRow;
import com.game.friend.store.pb.FriendEdgeRow;
import com.game.friend.store.pb.FriendRequestRow;
import com.game.friend.sweep.FriendSweep;
import com.game.pbmysql.PbMysql;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * xm-friend 的装配。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰外部系统的地方。
 */
@Configuration(proxyBeanMethods = false)
public class FriendConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FriendConfiguration.class);

    static final Duration WORKER_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    /** 结构同步连接的 socket 超时（咨询锁最多等 30 s，再加 DDL）。 */
    static final Duration SCHEMA_SYNC_NETWORK_TIMEOUT = Duration.ofMinutes(2);

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public FriendMetrics friendMetrics(MeterRegistry meterRegistry) {
        return new FriendMetrics(meterRegistry);
    }

    /**
     * 四张表经 xm-pbmysql 建表 / 只扩不缩地同步（在一条自动提交的连接上，整轮持咨询锁）；结构漂移即启动失败，由人工迁移。
     */
    @Bean
    public PbMysql friendTables(DataSource dataSource) throws SQLException {
        PbMysql db = new PbMysql();
        db.register(FriendEdgeRow.getDefaultInstance());
        db.register(FriendRequestRow.getDefaultInstance());
        db.register(FriendCapacityRow.getDefaultInstance());
        db.register(FriendBlockRow.getDefaultInstance());
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            // 连接串的 socketTimeout（4 s）是给请求流量的；结构同步要等 30 s 的咨询锁、跑 DDL，这条连接上临时放宽，用完还原
            int socketTimeout = c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run, (int) SCHEMA_SYNC_NETWORK_TIMEOUT.toMillis());
            try {
                db.syncAll(c);
            } finally {
                if (!c.isClosed()) {
                    c.setNetworkTimeout(Runnable::run, socketTimeout);
                }
            }
        }
        log.info("好友表已同步: friend / friend_request / friend_capacity / friend_block");
        return db;
    }

    /** {@code friendTables} 参数只为保证「表同步完成之后才有存储」。 */
    @Bean
    public FriendStore friendStore(DataSource dataSource, PbMysql friendTables, FriendProperties props,
                                   FriendMetrics metrics) {
        return new JdbcFriendStore(dataSource, props.limits(), System::currentTimeMillis,
                (int) Math.max(1, props.queryTimeout().toSeconds()), metrics);
    }

    @Bean
    public PlayerPresenceDirectory playerPresenceDirectory(RedissonClient redis) {
        return new PlayerPresenceDirectory(redis);
    }

    @Bean
    public PlayerPushes playerPushes(RedissonClient redis, PlayerPresenceDirectory directory) {
        return new PlayerPushes(redis, directory);
    }

    @Bean
    public FriendCache friendCache(RedissonClient redis, FriendProperties props, FriendMetrics metrics) {
        return new FriendCache(new RedissonFriendCacheRedis(redis), props.cacheTtl(), metrics);
    }

    @Bean
    public FriendRequestQuota friendRequestQuota(RedissonClient redis, FriendProperties props, FriendMetrics metrics) {
        return FriendRequestQuota.redisson(redis, props.requestQuotaPerMinute(), metrics);
    }

    @Bean
    public PlayerProfiles playerProfiles(DataSource dataSource, FriendProperties props) {
        return new PlayerProfiles(dataSource, (int) Math.max(1, props.queryTimeout().toSeconds()));
    }

    @Bean
    public FriendService friendService(FriendStore store, FriendCache cache, FriendRequestQuota quota,
                                       PlayerProfiles profiles, PlayerPresenceDirectory presence, PlayerPushes pushes,
                                       FriendMetrics metrics, FriendProperties props, MessageIdRegistry registry) {
        int listLimit = props.listReadHardLimit();
        return new FriendService(store, cache, quota, profiles::load,
                ids -> presence.findAllStrictAsync(ids, listLimit),
                pushes::pushToPlayer, metrics, listLimit, props.pushTimeout(), System::currentTimeMillis,
                registry.requireId(FriendDispatcher.SERVICE, "NotifyFriendEvent"));
    }

    /** 推荐（mutual / random 两条 SQL）与在线目录（SCAN {@code xm:presence:*} + player 表）。 */
    @Bean
    public RecommendService recommendService(DataSource dataSource, RedissonClient redis, PlayerProfiles profiles,
                                             PlayerPresenceDirectory presence, FriendMetrics metrics, FriendProperties props) {
        int queryTimeout = (int) Math.max(1, props.queryTimeout().toSeconds());
        RecommendStore store = new RecommendStore(dataSource, queryTimeout, RecommendPivot::pick);
        OnlineDirectory directory = new OnlineDirectory(new RedissonDirectoryRedis(redis, RedisKeys.presencePrefix()),
                RedisKeys.presencePrefix(), profiles::loadStrictOnce);
        FriendProperties.Recommend r = props.recommend();
        return new RecommendService(store, directory, DirectoryQuota.redisson(redis, metrics), profiles::loadStrict,
                ids -> presence.findAllStrictAsync(ids, props.listReadHardLimit()), metrics, r.defaultLimit(), r.maxLimit(),
                r.maxExclude());
    }

    /** 后台清理：终态好友申请与零好友容量行（缺省 report_only 只数不删）。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    public FriendSweep friendSweep(DataSource dataSource, FriendMetrics metrics, FriendProperties props) {
        FriendProperties.Sweep s = props.sweep();
        return new FriendSweep(new SweepStore(dataSource, (int) Math.max(1, props.queryTimeout().toSeconds())),
                s.mode(), s.interval(), s.retentionDays(), s.batchLimit(), System::currentTimeMillis,
                new FriendSweep.Gauges() {
                    @Override
                    public void pendingRows(String mode, long value) {
                        metrics.sweepPendingRows(mode, value);
                    }

                    @Override
                    public void idleCapacityRows(String mode, long value) {
                        metrics.sweepIdleCapacityRows(mode, value);
                    }
                });
    }

    @Bean(destroyMethod = "close")
    public FriendWorkerPool friendWorkerPool(FriendProperties props) {
        return new FriendWorkerPool(props.workerThreads(), props.workerQueueCapacity(), WORKER_DRAIN_TIMEOUT);
    }

    @Bean
    public FriendDispatcher friendDispatcher(MessageIdRegistry registry, FriendService service, RecommendService recommend,
                                             FriendWorkerPool workers, FriendMetrics metrics, FriendProperties props) {
        FriendDispatcher dispatcher = new FriendDispatcher(registry, service, recommend, workers, metrics,
                props.requestBudget().toMillis());
        log.info("friend 接管的消息号={} 限额 好友={} 出站={} 入站={} 黑名单={} 每分钟申请={}", dispatcher.routedMessageIds(),
                props.maxFriends(), props.maxPendingRequests(), props.maxIncomingRequests(), props.maxBlocks(),
                props.requestQuotaPerMinute());
        return dispatcher;
    }

    /** Dubbo 调用鉴权密钥的启动检查（fail-fast）；签名 / 校验在 xm-api 的 Dubbo 过滤器里。 */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }
}
