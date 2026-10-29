package com.game.mail;
import com.game.api.asset.*;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.*;
import com.game.discovery.location.*;
import com.game.discovery.serviceauth.*;
import com.game.discovery.sql.*;
import com.game.pbmysql.PbMysql;
import com.game.proto.rank.*;
import com.game.table.ConfigTables;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
/** 构造业务前核对TiDB强读、契约、配表和秘密；没有可用凭据时拒启。 */
@Configuration(proxyBeanMethods = false)
public class MailConfiguration {
    @Bean
    static StrongTiDbConnectionPolicy connectionPolicy(org.springframework.core.env.Environment environment) {
        // BPP 早于普通配置 bean 装配；从 Spring Environment 取值，禁止生产通过显式 false 降级。
        boolean require = environment.getProperty("game.mail.require-tidb", Boolean.class, true);
        if (!require && com.game.common.RunMode.parse(environment.getProperty("game.run-mode"))
                == com.game.common.RunMode.PROD) {
            throw new IllegalStateException("生产邮件必须启用 game.mail.require-tidb 的 TiDB 强读门禁");
        }
        return new StrongTiDbConnectionPolicy("mail", require);
    }
    @Bean
    DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }
    @Bean
    PbMysql domainTables(DataSource dataSource, DubboCallAuth auth, @Value("${game.mail.require-tidb:true}") boolean require)
            throws Exception {
        try (var connection = dataSource.getConnection()) {
            StrongTiDbConnectionPolicy.verify(connection, require);
        }
        return MailTables.sync(dataSource);
    }
    @Bean
    ConfigTables configTables(@Value("${game.table-dir:config-data/tables}") String path) {
        return ConfigTables.load(Path.of(path));
    }
    @Bean
    AuthorityJdbc jdbc(DataSource source, PbMysql domainTables) {
        return new AuthorityJdbc(source);
    }
    @Bean
    MailStore store(AuthorityJdbc jdbc, ConfigTables tables) {
        return new MailStore(jdbc, new AuthorityIds(jdbc, "mail_id_sequence"), tables.item()::contains, System::currentTimeMillis);
    }
    @Bean
    ServiceCallSignatures signatures() {
        var secrets = new HashMap<String, String>();
        for (String role : List.of("admin", "guild", "rank", "trade", "battle")) {
            String secret = System.getenv("MAIL_PRODUCER_SECRET_" + role.toUpperCase(Locale.ROOT));
            if (secret != null && !secret.isBlank())
                secrets.put(role, secret);
        }
        return new ServiceCallSignatures(secrets, System::currentTimeMillis);
    }
    @Bean(destroyMethod = "close")
    DomainClientDispatch clientDispatch(MailStore store) {
        return DomainClientDispatch.withSession(MessageIdRegistry.loadFromClasspath(), "ClientPlayerMail",
                Set.of("ListMails", "ReadMail", "ClaimMailAttachments", "DeleteMails", "ClearReadMails"),
                (method, session, request, budget) -> MailProvider.client(
                        store.forActor(session), method, session.getPlayerId(), request, budget), MailProvider::error);
    }
    @Bean
    MailNotifications notifications(
            org.redisson.api.RedissonClient redis, RedisKeys keys, io.micrometer.core.instrument.MeterRegistry metrics) {
        var directory = new com.game.discovery.presence.PlayerPresenceDirectory(redis, keys);
        return new MailNotifications(new com.game.discovery.presence.PlayerPushes(redis, keys, directory),
                MessageIdRegistry.loadFromClasspath().requireId("ClientPlayerMail", "NotifyMailChanged"), metrics);
    }
    @Bean(destroyMethod = "close")
    SceneAssetOpClients assetClients() {
        return new SceneAssetOpClients("game-mail-asset");
    }
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService assetTimer() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("mail-asset-timer").daemon(true).factory());
    }
    @Bean(destroyMethod = "close")
    MailDeliveryWorker deliveryWorker(MailStore store, DataSource source, org.redisson.api.RedissonClient redis, RedisKeys keys,
            SceneAssetOpClients clients, ScheduledExecutorService assetTimer) {
        var scenes = new NodeDirectory<SceneNodeInfo>(redis, keys, NodeTypes.SCENE, SceneNodeInfo.parser());
        var locator = new SceneAssetLocator(new PlayerLocationDirectory(redis, keys), scenes, result -> {});
        var delivery = new com.game.discovery.asset.ReliableAssetDelivery(
                locator, clients, "mail", System.getenv("GAME_ASSET_OP_SECRET_MAIL"), assetTimer, System::currentTimeMillis);
        return new MailDeliveryWorker(store, delivery, new com.game.player.store.asset.PersistedAssetLedgerReader(source));
    }
}
