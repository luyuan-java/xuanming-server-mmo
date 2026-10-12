package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下 game-data 的 Kafka 消费组缺省值与 client.id。 */
@Tag("namespace")
class DataKafkaNamesNamespaceTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    private static DataProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("game.data", DataProperties.class);
    }

    @Test
    void 消费组缺省与client_id随命名空间_显式覆盖照旧() {
        DataProperties p = bind(Map.of());
        assertThat(p.transactionLog().group()).isEqualTo("nstest-data-transaction-log");
        assertThat(p.playerSnapshot().group()).isEqualTo("nstest-data-player-snapshot");
        assertThat(DataNode.clientId(p.transactionLog().group())).isEqualTo("nstest-data-nstest-data-transaction-log");
        assertThat(DataConfiguration.adminClientId()).isEqualTo("nstest-data-admin");

        DataProperties partial = bind(Map.of("game.data.player-snapshot.insert-chunk", "3"));
        assertThat(partial.playerSnapshot().group()).as("只配别的字段时组仍取缺省").isEqualTo("nstest-data-player-snapshot");
        assertThat(bind(Map.of("game.data.transaction-log.group", "g2")).transactionLog().group()).isEqualTo("g2");
        assertThatThrownBy(() -> bind(Map.of("game.data.transaction-log.group", " "))).as("显式空白照旧拒绝")
                .isInstanceOf(BindException.class);
    }
}
