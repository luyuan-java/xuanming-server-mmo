package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 金样：缺省部署命名空间（{@code game}）下，game-data 的 Kafka 消费组缺省值与 client.id 与引入命名空间之前逐字节相同。 */
class DataKafkaNamesGoldenTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));

    /** 只看名字：DataNode 的构造器不碰别的依赖，其余参数给 null。 */
    static DataNode node(KafkaNames names, Map<String, String> settings) {
        DataProperties props = new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("game.data", DataProperties.class);
        return new DataNode(null, props, names, null, null, null, null, null, null);
    }

    @Test
    void 消费组缺省与client_id与金样相同() {
        DataNode node = node(KAFKA_NAMES, Map.of());
        assertThat(node.transactionLogGroup()).isEqualTo("game-data-transaction-log");
        assertThat(node.playerSnapshotGroup()).isEqualTo("game-data-player-snapshot");
        assertThat(node.clientId(node.transactionLogGroup())).isEqualTo("game-data-game-data-transaction-log");
        assertThat(node.clientId(node.playerSnapshotGroup())).isEqualTo("game-data-game-data-player-snapshot");
        assertThat(node.clientId("g2")).as("显式覆盖的组").isEqualTo("game-data-g2");
        assertThat(KAFKA_NAMES.dataAdminClientId()).as("DataConfiguration#dataNode 的 topic 管理客户端").isEqualTo("game-data-admin");
    }
}
