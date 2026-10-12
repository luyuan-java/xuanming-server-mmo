package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.BattleResultTopics;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import org.junit.jupiter.api.Test;

/**
 * 金样：缺省部署命名空间（{@code game}）下，battle 用到的 Kafka 名字——对局结果生产者的 client.id（{@code BattleConfiguration} 用控制面通告地址
 * 与端口调 {@link KafkaNames#battleResultClientId}）与对局结果 topic——与引入命名空间之前逐字节相同（装配后的主值另由 BattleConfigurationTest 钉住）。
 */
class BattleKafkaNamesGoldenTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));

    @Test
    void 对局结果client_id与金样相同() {
        assertThat(KAFKA_NAMES.battleResultClientId("127.0.0.1", 21200)).isEqualTo("game-battle-result-127.0.0.1-21200");
        assertThat(KAFKA_NAMES.battleResultClientId("127.0.0.1", 21200) + "-admin").isEqualTo("game-battle-result-127.0.0.1-21200-admin");
    }

    @Test
    void 对局结果topic与金样相同() {
        assertThat(new BattleResultTopics(KAFKA_NAMES).name(1)).isEqualTo("game-battle-result-g1");
    }
}
