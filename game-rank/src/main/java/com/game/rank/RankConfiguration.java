package com.game.rank;
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
public class RankConfiguration {
    @Bean
    static StrongTiDbConnectionPolicy connectionPolicy(org.springframework.core.env.Environment environment) {
        // BPP 早于普通配置 bean 装配；从 Spring Environment 取值，禁止生产通过显式 false 降级。
        boolean require = environment.getProperty("game.rank.require-tidb", Boolean.class, true);
        if (!require && com.game.common.RunMode.parse(environment.getProperty("game.run-mode"))
                == com.game.common.RunMode.PROD) {
            throw new IllegalStateException("生产排行榜必须启用 game.rank.require-tidb 的 TiDB 强读门禁");
        }
        return new StrongTiDbConnectionPolicy("rank", require);
    }
    @Bean
    DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }
    @Bean
    PbMysql domainTables(DataSource dataSource, DubboCallAuth auth, @Value("${game.rank.require-tidb:true}") boolean require)
            throws Exception {
        try (var connection = dataSource.getConnection()) {
            StrongTiDbConnectionPolicy.verify(connection, require);
        }
        return RankTables.sync(dataSource);
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
    RankStore store(AuthorityJdbc jdbc, ConfigTables tables) {
        return new RankStore(jdbc, new AuthorityIds(jdbc, "rank_id_sequence"), tables.item()::contains, System::currentTimeMillis);
    }
    @Bean
    ServiceCallSignatures signatures() {
        var secrets = new HashMap<String, String>();
        for (String role : List.of("admin", "match", "guild", "battle")) {
            String secret = System.getenv("RANK_PRODUCER_SECRET_" + role.toUpperCase(Locale.ROOT));
            if (secret != null && !secret.isBlank())
                secrets.put(role, secret);
        }
        return new ServiceCallSignatures(secrets, System::currentTimeMillis);
    }
    @Bean(destroyMethod = "close")
    DomainClientDispatch clientDispatch(RankStore store) {
        return new DomainClientDispatch(MessageIdRegistry.loadFromClasspath(), "ClientPlayerRank",
                Set.of("GetRankPage", "GetRankAround"), (method, player, request, budget) -> switch (method) {
                    case "GetRankPage" -> store.page(player, (GetRankPageRequest) request, budget);
                    case "GetRankAround" -> store.around(player, (GetRankAroundRequest) request, budget);
                    default -> throw new IllegalArgumentException();
                }, RankProvider::error);
    }
    @Bean(destroyMethod = "close")
    RankRewardWorker rewardWorker(RankStore store) {
        return new RankRewardWorker(store, System.getenv("MAIL_PRODUCER_SECRET_RANK"));
    }
}
