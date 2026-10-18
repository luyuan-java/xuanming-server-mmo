package com.game.player.store;

import com.game.common.RunMode;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 玩家写库与证明读取共用的强读门禁；独立于 Mapper 自动装配，game-data 自建 PlayerStore 也必须经过。 */
@AutoConfiguration
public class PlayerSqlAuthorityAutoConfiguration {
    static boolean requireTiDb(Environment environment) {
        boolean require = environment.getProperty("game.player-store.require-tidb", Boolean.class, true);
        if (!require && RunMode.parse(environment.getProperty("game.run-mode")) == RunMode.PROD) {
            throw new IllegalStateException("生产玩家权威库不能关闭 TiDB 强读；旧 MySQL 只允许明确 dev/test");
        }
        return require;
    }

    @Bean
    static StrongTiDbConnectionPolicy playerStoreConnectionPolicy(Environment environment) {
        return new StrongTiDbConnectionPolicy("player-store", requireTiDb(environment));
    }

    @Bean
    InitializingBean playerSqlAuthority(DataSource source, Environment environment) {
        boolean require = requireTiDb(environment);
        return () -> {
            try (var connection = source.getConnection()) {
                StrongTiDbConnectionPolicy.verify(connection, require);
            }
        };
    }
}
