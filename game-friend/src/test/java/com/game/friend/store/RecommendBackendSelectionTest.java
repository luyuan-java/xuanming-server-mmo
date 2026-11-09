package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/** 只验证参数选择，不连接数据库；每种后端的业务断言仍在RecommendDatabaseTest实际执行。 */
class RecommendBackendSelectionTest {
    @Test void absentSelectionPreservesBothOriginalTargets() {
        assertThat(RecommendDatabaseTest.targets(new Properties()).map(RecommendDatabaseTest.Target::property).toList())
                .containsExactly("mysql", "tidb");
    }

    @Test void explicitSelectionRequiresOnlyItsOwnActualEndpoint() {
        for (String backend : new String[]{"mysql", "tidb"}) {
            Properties values = new Properties();
            values.setProperty("game.it.friend.recommend.backend", backend);
            values.setProperty("game.it." + backend, "jdbc:mysql://127.0.0.1:" + (backend.equals("mysql") ? "13316" : "24000"));
            var targets = RecommendDatabaseTest.targets(values).toList();
            assertThat(targets).hasSize(1);
            assertThat(targets.getFirst().property()).isEqualTo(backend);
            assertThat(targets.getFirst().dialect()).isEqualTo(backend.equals("mysql") ? RecommendSql.Dialect.MYSQL : RecommendSql.Dialect.TIDB);
        }
    }

    @Test void invalidOrMissingSelectedEndpointFailsBeforeParameterExecution() {
        Properties values = new Properties();
        values.setProperty("game.it.friend.recommend.backend", "unknown");
        assertThatThrownBy(() -> RecommendDatabaseTest.targets(values)).isInstanceOf(IllegalArgumentException.class);
        for (String backend : new String[]{"mysql", "tidb"}) {
            values.setProperty("game.it.friend.recommend.backend", backend);
            values.setProperty("game.it." + (backend.equals("mysql") ? "tidb" : "mysql"), "jdbc:mysql://127.0.0.1:1");
            values.remove("game.it." + backend);
            assertThatThrownBy(() -> RecommendDatabaseTest.targets(values)).isInstanceOf(IllegalStateException.class);
            values.setProperty("game.it." + backend, " ");
            assertThatThrownBy(() -> RecommendDatabaseTest.targets(values)).isInstanceOf(IllegalStateException.class);
        }
    }
}
