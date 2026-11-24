package com.game.gm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.MailAdminService;
import com.game.api.MatchInternalService;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** {@code game-gm-admin} 核心业务装配（不强依赖 Dubbo 启动，测试可注入替身 {@link GmGamePort}）。 */
@Configuration(proxyBeanMethods = false)
public class GmAdminConfiguration {

    public static final String MAIL_ADMIN_SECRET_ENV = "MMORPG_MAIL_ADMIN_SECRET";
    public static final String PLAYER_COMMAND_SECRET_ENV = "MMORPG_GM_PLAYER_COMMAND_SECRET";

    @Bean
    @ConditionalOnMissingBean
    public GmAdminAuth gmAdminAuth(GmAdminProperties properties) {
        return new GmAdminAuth(properties.operators());
    }

    @Bean
    @ConditionalOnMissingBean
    public GmCommandJournal gmCommandJournal(JdbcTemplate jdbc, ObjectMapper mapper,
                                             PlatformTransactionManager txManager,
                                             GmAdminProperties properties) {
        if (Boolean.TRUE.equals(properties.autoInitSchema())) {
            initSchema(jdbc);
        }
        return new GmCommandJournal(jdbc, mapper, txManager);
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
        String mailSecret = Boolean.TRUE.equals(properties.mailEnabled()) ? env(MAIL_ADMIN_SECRET_ENV) : "";
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

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.strip();
    }
}
