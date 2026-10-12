package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 非缺省部署命名空间（直接构造 {@code nstest} 的 {@link KafkaNames}）下 game-data 的 Kafka 消费组缺省值与 client.id；显式覆盖的组照旧。 */
class DataKafkaNamesNamespaceTest {

    private static final KafkaNames NSTEST = new KafkaNames(GameNamespace.of("nstest"));
    private static final KafkaNames GAME = new KafkaNames(GameNamespace.of("game"));

    @Test
    void 消费组缺省与client_id随命名空间_显式覆盖照旧() {
        DataNode node = DataKafkaNamesGoldenTest.node(NSTEST, Map.of());
        assertThat(node.transactionLogGroup()).isEqualTo("nstest-data-transaction-log");
        assertThat(node.playerSnapshotGroup()).isEqualTo("nstest-data-player-snapshot");
        assertThat(node.clientId(node.transactionLogGroup())).isEqualTo("nstest-data-nstest-data-transaction-log");
        assertThat(NSTEST.dataAdminClientId()).isEqualTo("nstest-data-admin");

        DataNode partial = DataKafkaNamesGoldenTest.node(NSTEST, Map.of("game.data.player-snapshot.insert-chunk", "3"));
        assertThat(partial.playerSnapshotGroup()).as("只配别的字段时组仍取缺省").isEqualTo("nstest-data-player-snapshot");
        DataNode explicit = DataKafkaNamesGoldenTest.node(NSTEST, Map.of("game.data.transaction-log.group", "g2"));
        assertThat(explicit.transactionLogGroup()).isEqualTo("g2");
        assertThat(explicit.clientId(explicit.transactionLogGroup())).isEqualTo("nstest-data-g2");
        assertThatThrownBy(() -> DataKafkaNamesGoldenTest.node(NSTEST, Map.of("game.data.transaction-log.group", " ")))
                .as("显式空白照旧拒绝").isInstanceOf(BindException.class);
    }

    @Test
    void 同一个JVM里两个命名空间互不影响() {
        DataNode game = DataKafkaNamesGoldenTest.node(GAME, Map.of());
        DataNode nstest = DataKafkaNamesGoldenTest.node(NSTEST, Map.of());
        assertThat(game.transactionLogGroup()).isEqualTo("game-data-transaction-log");
        assertThat(nstest.transactionLogGroup()).isEqualTo("nstest-data-transaction-log");
        assertThat(game.clientId(game.playerSnapshotGroup())).isEqualTo("game-data-game-data-player-snapshot");
        assertThat(nstest.clientId(nstest.playerSnapshotGroup())).isEqualTo("nstest-data-nstest-data-player-snapshot");
    }
}
