package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 金样：缺省部署命名空间（{@code game}）下，game-data 的 Kafka 消费组缺省值与 client.id 与引入命名空间之前逐字节相同。 */
class DataKafkaNamesGoldenTest {

    @BeforeAll
    static void 只在缺省命名空间里跑() {
        assertThat(GameNamespace.get())
                .as("金样只在缺省命名空间里成立；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的 game）")
                .isEqualTo("game");
    }

    @Test
    void 消费组缺省与client_id与金样相同() {
        DataProperties p = new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("game.data", DataProperties.class);
        assertThat(p.transactionLog().group()).isEqualTo("game-data-transaction-log");
        assertThat(p.playerSnapshot().group()).isEqualTo("game-data-player-snapshot");
        assertThat(DataNode.clientId(p.transactionLog().group())).isEqualTo("game-data-game-data-transaction-log");
        assertThat(DataNode.clientId(p.playerSnapshot().group())).isEqualTo("game-data-game-data-player-snapshot");
        assertThat(DataNode.clientId("g2")).as("显式覆盖的组").isEqualTo("game-data-g2");
        assertThat(DataConfiguration.adminClientId()).isEqualTo("game-data-admin");
    }
}
