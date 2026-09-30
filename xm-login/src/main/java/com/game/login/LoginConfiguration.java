package com.game.login;

import com.game.api.SceneDirectoryService;
import com.game.common.id.Snowflake;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.character.CharacterRules;
import com.game.login.character.PlayerIdGenerator;
import com.game.login.character.TableCharacterRules;
import com.game.login.dispatch.ClientMessageDispatcher;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.LoginWorkerPool;
import com.game.login.handler.CreatePlayerHandler;
import com.game.login.handler.DisconnectHandler;
import com.game.login.handler.EnterGameHandler;
import com.game.login.handler.LeaveGameHandler;
import com.game.login.handler.LoginHandler;
import com.game.login.ownership.OwnerTakeovers;
import com.game.login.ownership.RedisOwnerTakeovers;
import com.game.player.store.PlayerStore;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * xm-login 的装配。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰全局单例（配表）与外部系统的地方。
 */
@Configuration(proxyBeanMethods = false)
public class LoginConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoginConfiguration.class);

    /**
     * player_id 雪花 worker 的租约作用域。故意<b>不按 zone 分</b>：player 表是全服一张、player_id 全服唯一，
     * 若按 zone 各占 [0, 1023]，两个 zone 的 login 会拿到同一个 worker、发出逐位相同的号。
     * zone 号从 1 开始，0 在这里表示「全服」。
     */
    static final int PLAYER_ID_LEASE_SCOPE = 0;
    static final int WORKER_MIN = 0;
    static final int WORKER_MAX = Snowflake.MAX_WORKER;
    static final Duration LEASE_TTL = Duration.ofSeconds(15);
    static final Duration WORKER_DRAIN_TIMEOUT = Duration.ofSeconds(15);

    /** Dubbo 调用本身 3s 超时、不重试（重试会把总耗时拖过客户端预算）；scene-manager 不在时不影响 login 启动。 */
    @DubboReference(check = false, url = "${xm.dubbo.scene-manager-url:}", timeout = 3000, retries = 0)
    private SceneDirectoryService sceneDirectory;

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    /** 同步加载全部配置表并自检；失败即启动失败，Dubbo 不会暴露服务。 */
    @Bean
    public CharacterRules characterRules(@Value("${xm.table-dir:config-data/tables}") String tableDir) {
        TableCharacterRules rules = TableCharacterRules.load(Path.of(tableDir));
        log.info("配置表已加载 dir={} 默认职业={} 名字规则={}",
                Path.of(tableDir).toAbsolutePath(), rules.defaultClassId(), rules.roleNameRules());
        return rules;
    }

    @Bean
    public LoginAuthenticator loginAuthenticator(LoginProperties props,
                                                 @Value("${XM_LOGIN_DEV_PASSWORD:}") String devPassword) {
        if (!props.devMode()) {
            log.info("xm.login.mode=prod：开发口令认证关闭，口令登录一律失败");
            return LoginAuthenticator.passwordDisabled();
        }
        if (devPassword == null || devPassword.isEmpty()) {
            throw new IllegalStateException("xm.login.mode=dev 需要环境变量 XM_LOGIN_DEV_PASSWORD（开发口令），拒绝启动");
        }
        log.warn("开发口令认证已启用（xm.login.mode=dev），账号前缀白名单={}；生产环境必须设为 prod",
                props.devAccountPrefixes());
        return LoginAuthenticator.withDevPassword(new DevPasswordRule(devPassword, props.devAccountPrefixes()));
    }

    @Bean(destroyMethod = "close")
    public LoginWorkerPool loginWorkerPool(LoginProperties props) {
        return new LoginWorkerPool(props.workerThreads(), props.workerQueueCapacity(), WORKER_DRAIN_TIMEOUT);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService loginLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("login-lease").daemon(true).factory());
    }

    /**
     * 雪花 worker 租约。租约无效（丢失，或续期滞后超过 2/3 TTL）期间 {@link PlayerIdGenerator} 拒绝发号，
     * 建角回 2020（登录 / 进游戏不受影响）；续期滞后恢复后自动恢复，丢失则需重启。
     */
    @Bean(destroyMethod = "close")
    public NodeIdLease playerIdLease(RedissonClient redis, ScheduledExecutorService loginLeaseScheduler) {
        return NodeIdLease.acquire(redis, loginLeaseScheduler, NodeTypes.LOGIN, PLAYER_ID_LEASE_SCOPE,
                WORKER_MIN, WORKER_MAX, UUID.randomUUID().toString(), LEASE_TTL,
                () -> log.error("player_id 雪花 worker 租约丢失：停止建角（新的 CreatePlayer 一律失败），需要重启本进程"));
    }

    @Bean
    public PlayerIdGenerator playerIdGenerator(NodeIdLease playerIdLease) {
        log.info("player_id 雪花 worker={}", playerIdLease.nodeId());
        return new PlayerIdGenerator(new Snowflake(playerIdLease.nodeId()), playerIdLease::isValid);
    }

    @Bean
    public LoginHandler loginHandler(LoginAuthenticator authenticator, PlayerStore store) {
        return new LoginHandler(authenticator, store);
    }

    @Bean
    public CreatePlayerHandler createPlayerHandler(PlayerStore store, CharacterRules characterRules,
                                                   PlayerIdGenerator playerIds, LoginProperties props,
                                                   @Value("${xm.zone-id:1}") int zoneId) {
        SecureRandom random = new SecureRandom();
        return new CreatePlayerHandler(store, characterRules, playerIds, () -> random.nextInt(256),
                zoneId, props.maxPlayersPerAccount());
    }

    /** 归属接管请求（Redis pub/sub，全部 scene 节点订阅）。 */
    @Bean
    public OwnerTakeovers ownerTakeovers(RedissonClient redis) {
        return new RedisOwnerTakeovers(redis);
    }

    @Bean
    public EnterGameHandler enterGameHandler(PlayerStore store, OwnerTakeovers ownerTakeovers,
                                             LoginWorkerPool loginWorkerPool, LoginProperties props,
                                             @Value("${xm.zone-id:1}") int zoneId) {
        return new EnterGameHandler(store, sceneDirectory, ownerTakeovers, loginWorkerPool, zoneId,
                props.sceneAssignTimeout(), props.ownerClaimWait());
    }

    /**
     * Dubbo 调用鉴权密钥的启动检查（fail-fast，报错信息明确）。真正的签名 / 校验在 xm-api 的 Dubbo 过滤器里，
     * 它们自己也从同一个环境变量读密钥、缺失即实例化失败。
     */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    @Bean
    public LeaveGameHandler leaveGameHandler() {
        return new LeaveGameHandler();
    }

    @Bean
    public DisconnectHandler disconnectHandler() {
        return new DisconnectHandler();
    }

    @Bean
    public ClientMessageDispatcher clientMessageDispatcher(MessageIdRegistry registry,
                                                           List<ClientMessageHandler<?>> handlers,
                                                           LoginWorkerPool loginWorkerPool) {
        ClientMessageDispatcher dispatcher = new ClientMessageDispatcher(registry, handlers, loginWorkerPool);
        log.info("login 接管的消息号={}", dispatcher.routedMessageIds());
        return dispatcher;
    }
}
