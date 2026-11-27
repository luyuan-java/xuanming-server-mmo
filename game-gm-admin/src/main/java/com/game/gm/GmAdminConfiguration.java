package com.game.gm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.DubboGroups;
import com.game.api.MailAdminService;
import com.game.api.MatchInternalService;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.sql.DataSource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** {@code game-gm-admin} 核心业务装配（不强依赖 Dubbo 启动，测试可注入替身 {@link GmGamePort}）。 */
@Configuration(proxyBeanMethods = false)
public class GmAdminConfiguration {

    /** 与 game-mail {@code MailConfiguration.signatures()} 角色 {@code admin} 统一的标准环境变量名。 */
    public static final String CANONICAL_MAIL_ADMIN_SECRET_ENV = "MAIL_PRODUCER_SECRET_ADMIN";
    /** 兼容历史部署的旧变量名；当 {@link #CANONICAL_MAIL_ADMIN_SECRET_ENV} 未设置时回退读取。 */
    public static final String MAIL_ADMIN_SECRET_ENV = "MMORPG_MAIL_ADMIN_SECRET";
    public static final String PLAYER_COMMAND_SECRET_ENV = "MMORPG_GM_PLAYER_COMMAND_SECRET";

    @Bean
    @ConditionalOnMissingBean
    public GmAdminAuth gmAdminAuth(
            GmAdminProperties properties,
            ObjectProvider<OidcJwtVerifier> oidcVerifier,
            @org.springframework.beans.factory.annotation.Value("${game.run-mode:prod}") String runModeRaw) {
        com.game.common.RunMode mode = com.game.common.RunMode.parse(runModeRaw);
        OidcJwtVerifier oidc = oidcVerifier.getIfAvailable();
        // 生产模式禁用静态口令表，仅认外部 OIDC 身份提供方 JWT
        boolean allowStatic = mode != com.game.common.RunMode.PROD;
        return new GmAdminAuth(properties.operators(), oidc, allowStatic);
    }

    @Bean
    @ConditionalOnMissingBean
    public GmCommandJournal gmCommandJournal(
            JdbcTemplate jdbc, ObjectMapper mapper,
            PlatformTransactionManager txManager,
            GmAdminProperties properties,
            @org.springframework.beans.factory.annotation.Value("${game.run-mode:prod}") String runModeRaw,
            @org.springframework.beans.factory.annotation.Value("${game.gm.allow-unapproved:false}") boolean allowUnapproved) {
        com.game.common.RunMode mode = com.game.common.RunMode.parse(runModeRaw);
        com.game.common.admin.DualControlValidator.assertProductionPolicy(mode, allowUnapproved);
        if (Boolean.TRUE.equals(properties.autoInitSchema())) {
            initSchema(jdbc);
        }
        boolean requireDualControl = mode == com.game.common.RunMode.PROD || !allowUnapproved;
        return new GmCommandJournal(jdbc, mapper, txManager).withDualControlRequired(requireDualControl);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "game.gm.redis-enabled", havingValue = "true", matchIfMissing = true)
    public PlayerPresenceDirectory gmPlayerPresenceDirectory(ObjectProvider<RedissonClient> redisProvider,
                                                             ObjectProvider<RedisKeys> keysProvider) {
        RedissonClient redis = redisProvider.getIfAvailable();
        RedisKeys keys = keysProvider.getIfAvailable();
        return (redis != null && keys != null) ? new PlayerPresenceDirectory(redis, keys) : null;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "game.gm.redis-enabled", havingValue = "true", matchIfMissing = true)
    public PlayerLocationDirectory gmPlayerLocationDirectory(ObjectProvider<RedissonClient> redisProvider,
                                                             ObjectProvider<RedisKeys> keysProvider) {
        RedissonClient redis = redisProvider.getIfAvailable();
        RedisKeys keys = keysProvider.getIfAvailable();
        return (redis != null && keys != null) ? new PlayerLocationDirectory(redis, keys) : null;
    }

    /** 显式开启 {@code game.gm.mail-enabled=true} 时挂载邮件管理 Dubbo 引用（直连或 Nacos）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "game.gm.mail-enabled", havingValue = "true")
    static class MailAdminClientConfiguration {

        @DubboReference(group = DubboGroups.MAIL, url = "${game.dubbo.mail-url:}", check = false, timeout = 5000, retries = 0,
                parameters = {org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY, com.game.api.rpc.DubboReconnectParameters.HEARTBEAT_MS,
                        org.apache.dubbo.remoting.Constants.LEAST_RECONNECT_DURATION_KEY, com.game.api.rpc.DubboReconnectParameters.LEAST_RECONNECT_MS})
        private MailAdminService mailAdminService;

        @Bean
        @ConditionalOnMissingBean
        MailAdminService gmMailAdminService() {
            return mailAdminService;
        }
    }

    /** 显式开启 {@code game.gm.match-ops-enabled=true} 时挂载匹配内部管理 Dubbo 引用（战斗剧本审核）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "game.gm.match-ops-enabled", havingValue = "true")
    static class MatchOpsClientConfiguration {

        @DubboReference(group = DubboGroups.MATCH, url = "${game.dubbo.match-url:}", check = false, timeout = 5000, retries = 0,
                parameters = {org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY, com.game.api.rpc.DubboReconnectParameters.HEARTBEAT_MS,
                        org.apache.dubbo.remoting.Constants.LEAST_RECONNECT_DURATION_KEY, com.game.api.rpc.DubboReconnectParameters.LEAST_RECONNECT_MS})
        private MatchInternalService matchInternalService;

        @Bean
        @ConditionalOnMissingBean
        MatchInternalService gmMatchInternalService() {
            return matchInternalService;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public GmGamePort gmGamePort(ObjectMapper mapper,
                                 GmAdminProperties properties,
                                 ObjectProvider<DataSource> dataSource,
                                 ObjectProvider<PlayerPresenceDirectory> presence,
                                 ObjectProvider<PlayerLocationDirectory> locations,
                                 ObjectProvider<MailAdminService> mailAdmin,
                                 ObjectProvider<MatchInternalService> matchInternal,
                                 ObjectProvider<DubboGmGamePort.PlayerCommandExecutor> playerExecutor) {
        DataSource ds = dataSource.getIfAvailable();
        PlayerProfiles profiles = ds == null ? null : new PlayerProfiles(maxWait -> ds.getConnection(), 3);
        PlayerHomeZones homeZones = ds == null ? null : new PlayerHomeZones(ds, Duration.ofMillis(1500));
        String mailSecret = Boolean.TRUE.equals(properties.mailEnabled())
                ? resolveMailAdminSecret(System::getenv) : "";
        String matchOpsSecret = Boolean.TRUE.equals(properties.matchOpsEnabled()) ? env(MatchInternalService.OPS_SECRET_ENV) : "";
        String playerCmdSecret = Boolean.TRUE.equals(properties.playerCommandsEnabled()) ? env(PLAYER_COMMAND_SECRET_ENV) : "";
        return new DubboGmGamePort(
                mapper, profiles, homeZones,
                presence.getIfAvailable(), locations.getIfAvailable(),
                mailAdmin.getIfAvailable(), mailSecret,
                matchInternal.getIfAvailable(), matchOpsSecret,
                playerExecutor.getIfAvailable(), playerCmdSecret,
                properties.callBudgetMs());
    }

    private static void initSchema(JdbcTemplate jdbc) {
        try (InputStream in = GmAdminConfiguration.class.getResourceAsStream("/db/gm-schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("缺少 /db/gm-schema.sql");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.strip();
                if (!trimmed.isEmpty()) {
                    jdbc.execute(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("初始化 GM 账本表失败", e);
        }
    }

    static String resolveMailAdminSecret(java.util.function.Function<String, String> lookup) {
        String canonical = lookup.apply(CANONICAL_MAIL_ADMIN_SECRET_ENV);
        if (canonical != null && !canonical.isBlank()) {
            return canonical.strip();
        }
        String legacy = lookup.apply(MAIL_ADMIN_SECRET_ENV);
        return legacy == null ? "" : legacy.strip();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.strip();
    }
}
