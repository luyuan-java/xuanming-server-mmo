package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.AuditTopics;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import org.junit.jupiter.api.Test;

/**
 * 金样：缺省部署命名空间（{@code game}）下，scene 审计用到的 Kafka 名字——生产者的 client.id（{@code SceneNode} 用 zone 与节点号调
 * {@link KafkaNames#sceneAuditClientId}，核对 topic 的 admin 客户端再加 {@code -admin}）与两个审计 topic——与引入命名空间之前逐字节相同。
 */
class SceneKafkaNamesGoldenTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));

    @Test
    void 审计client_id与金样相同() {
        assertThat(KAFKA_NAMES.sceneAuditClientId(1, 2)).isEqualTo("game-scene-audit-z1-n2");
        assertThat(KAFKA_NAMES.sceneAuditClientId(1, 2) + "-admin").isEqualTo("game-scene-audit-z1-n2-admin");
    }

    @Test
    void 审计topic与金样相同() {
        AuditTopics topics = new AuditTopics(KAFKA_NAMES);
        assertThat(topics.transactionLog(1).name()).isEqualTo("game-transaction-log-g1");
        assertThat(topics.playerSnapshot(1).name()).isEqualTo("game-player-snapshot-g1");
    }
}
