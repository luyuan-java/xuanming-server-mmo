package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 金样：缺省部署命名空间 {@code game} 下，Kafka 的每一个 topic 基名、消费组缺省值、client.id 与引入命名空间之前逐字节相同；
 * 非缺省命名空间只换前缀、后缀结构不变。
 */
class KafkaNamesGoldenTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));

    @Test
    void 缺省命名空间_全部名字与金样相同() {
        assertThat(KAFKA_NAMES.prefix()).isEqualTo("game-");
        assertThat(KAFKA_NAMES.namespace()).isEqualTo(GameNamespace.of("game"));
        assertThat(KAFKA_NAMES.transactionLogTopicBase()).isEqualTo("game-transaction-log");
        assertThat(KAFKA_NAMES.playerSnapshotTopicBase()).isEqualTo("game-player-snapshot");
        assertThat(KAFKA_NAMES.battleResultTopicBase()).isEqualTo("game-battle-result");
        assertThat(KAFKA_NAMES.dataTransactionLogGroup()).isEqualTo("game-data-transaction-log");
        assertThat(KAFKA_NAMES.dataPlayerSnapshotGroup()).isEqualTo("game-data-player-snapshot");
        assertThat(KAFKA_NAMES.matchRatingGroup()).isEqualTo("game-match-rating");
        assertThat(KAFKA_NAMES.guildTrialGroup()).isEqualTo("game-guild-trial");
        assertThat(KAFKA_NAMES.guildTrialClientId("guild-1")).isEqualTo("game-guild-trial-guild-1");
        assertThat(KAFKA_NAMES.dataAdminClientId()).isEqualTo("game-data-admin");
        assertThat(KAFKA_NAMES.dataConsumerClientId("game-data-transaction-log")).isEqualTo("game-data-game-data-transaction-log");
        assertThat(KAFKA_NAMES.dataConsumerClientId("game-data-player-snapshot")).isEqualTo("game-data-game-data-player-snapshot");
        assertThat(KAFKA_NAMES.matchAdminClientId()).isEqualTo("game-match-admin");
        assertThat(KAFKA_NAMES.matchRatingClientId("match-1")).isEqualTo("game-match-rating-match-1");
        assertThat(KAFKA_NAMES.sceneAuditClientId(1, 2)).isEqualTo("game-scene-audit-z1-n2");
        assertThat(KAFKA_NAMES.battleResultClientId("10.0.0.5", 21200)).isEqualTo("game-battle-result-10.0.0.5-21200");
        assertThat(KAFKA_NAMES).hasToString("KafkaNames[game]");
    }

    @Test
    void 非缺省命名空间_只换前缀() {
        KafkaNames ns = new KafkaNames(GameNamespace.of("nstest"));
        assertThat(ns.prefix()).isEqualTo("nstest-");
        assertThat(ns.transactionLogTopicBase()).isEqualTo("nstest-transaction-log");
        assertThat(ns.playerSnapshotTopicBase()).isEqualTo("nstest-player-snapshot");
        assertThat(ns.battleResultTopicBase()).isEqualTo("nstest-battle-result");
        assertThat(ns.dataTransactionLogGroup()).isEqualTo("nstest-data-transaction-log");
        assertThat(ns.dataPlayerSnapshotGroup()).isEqualTo("nstest-data-player-snapshot");
        assertThat(ns.matchRatingGroup()).isEqualTo("nstest-match-rating");
        assertThat(ns.dataAdminClientId()).isEqualTo("nstest-data-admin");
        assertThat(ns.dataConsumerClientId("nstest-data-transaction-log")).isEqualTo("nstest-data-nstest-data-transaction-log");
        assertThat(ns.matchAdminClientId()).isEqualTo("nstest-match-admin");
        assertThat(ns.matchRatingClientId("m1")).isEqualTo("nstest-match-rating-m1");
        assertThat(ns.sceneAuditClientId(3, 4)).isEqualTo("nstest-scene-audit-z3-n4");
        assertThat(ns.battleResultClientId("::1", 21200)).as("client.id 不收紧字符集").isEqualTo("nstest-battle-result-::1-21200");
        assertThat(KAFKA_NAMES.transactionLogTopicBase()).as("两个命名空间在同一个 JVM 里并存、互不影响").isEqualTo("game-transaction-log");
    }

    @Test
    void 没有静态入口_只能注入或构造实例() {
        assertThat(Arrays.stream(KafkaNames.class.getDeclaredMethods()).filter(m -> Modifier.isStatic(m.getModifiers())).map(Method::getName))
                .as("KafkaNames 不得有静态方法（静态入口就是绕过注入的全局值）").isEmpty();
        assertThat(Arrays.stream(KafkaNames.class.getDeclaredFields()).filter(f -> Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .map(f -> f.getName())).containsExactly("MAX_NAME_LENGTH");
    }
}
