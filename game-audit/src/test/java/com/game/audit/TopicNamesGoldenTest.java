package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import org.junit.jupiter.api.Test;

/**
 * 金样：缺省部署命名空间 {@code game} 下，审计与对局结果 topic 的名字与引入命名空间之前逐字节相同；非缺省命名空间只换前缀，
 * 代次、分区数、保留期不变。两个命名空间的实例在同一个 JVM 里并存。
 */
class TopicNamesGoldenTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("game"));

    @Test
    void 缺省命名空间_topic名字与金样相同() {
        AuditTopics audit = new AuditTopics(KAFKA_NAMES);
        BattleResultTopics battle = new BattleResultTopics(KAFKA_NAMES);
        assertThat(audit.transactionLogBase()).isEqualTo("game-transaction-log");
        assertThat(audit.playerSnapshotBase()).isEqualTo("game-player-snapshot");
        assertThat(audit.all(1)).extracting(TopicSpec::name).containsExactly("game-transaction-log-g1", "game-player-snapshot-g1");
        assertThat(audit.all(3)).extracting(TopicSpec::name).containsExactly("game-transaction-log-g3", "game-player-snapshot-g3");
        assertThat(battle.base()).isEqualTo("game-battle-result");
        assertThat(battle.name(1)).isEqualTo("game-battle-result-g1");
        assertThat(battle.spec(2).name()).isEqualTo("game-battle-result-g2");
    }

    @Test
    void 非缺省命名空间_只换前缀_代次与规格不变() {
        KafkaNames ns = new KafkaNames(GameNamespace.of("nstest"));
        AuditTopics audit = new AuditTopics(ns);
        BattleResultTopics battle = new BattleResultTopics(ns);
        assertThat(audit.transactionLogBase()).isEqualTo("nstest-transaction-log");
        assertThat(audit.playerSnapshotBase()).isEqualTo("nstest-player-snapshot");
        assertThat(audit.all(1)).extracting(TopicSpec::name).containsExactly("nstest-transaction-log-g1", "nstest-player-snapshot-g1");
        assertThat(audit.transactionLog(1).partitions()).isEqualTo(AuditTopics.TRANSACTION_LOG_PARTITIONS);
        assertThat(audit.playerSnapshot(1).configs()).isEqualTo(AuditTopics.RETENTION);
        assertThat(battle.base()).isEqualTo("nstest-battle-result");
        assertThat(battle.name(1)).isEqualTo("nstest-battle-result-g1");
        assertThat(battle.spec(1).partitions()).isEqualTo(BattleResultTopics.PARTITIONS);
        assertThat(AuditTopics.name("x", 2)).as("name(base, gen) 仍是纯函数").isEqualTo("x-g2");
        assertThat(new AuditTopics(KAFKA_NAMES).transactionLogBase()).as("与缺省命名空间的实例互不影响").isEqualTo("game-transaction-log");
    }
}
