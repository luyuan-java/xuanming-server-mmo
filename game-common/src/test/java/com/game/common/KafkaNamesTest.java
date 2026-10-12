package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Kafka 名字的后缀校验：topic / 消费组 / 固定 client.id 走 {@code name}（字符集与 249 字符上限），带变量的 client.id 走 {@code client}（只要求非空）。 */
class KafkaNamesTest {

    private static final KafkaNames KAFKA_NAMES = new KafkaNames(GameNamespace.of("nstest"));

    @Test
    void name_后缀非空_只许topic合法字符_全名不超过249() {
        assertThat(KAFKA_NAMES.name("match-admin")).isEqualTo("nstest-match-admin");
        assertThat(KAFKA_NAMES.name("A.b_c-1")).isEqualTo("nstest-A.b_c-1");
        assertThatThrownBy(() -> KAFKA_NAMES.name("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KAFKA_NAMES.name(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KAFKA_NAMES.name("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KAFKA_NAMES.name("a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KAFKA_NAMES.name("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KAFKA_NAMES.name("x".repeat(KafkaNames.MAX_NAME_LENGTH))).isInstanceOf(IllegalArgumentException.class);
        assertThat(KAFKA_NAMES.name("x".repeat(KafkaNames.MAX_NAME_LENGTH - "nstest-".length()))).hasSize(KafkaNames.MAX_NAME_LENGTH);
    }

    @Test
    void client_只要求非空_不收紧字符集() {
        assertThat(KAFKA_NAMES.client("battle-result-::1-21200")).isEqualTo("nstest-battle-result-::1-21200");
        assertThat(KAFKA_NAMES.client("data-my group")).isEqualTo("nstest-data-my group");
        assertThatThrownBy(() -> KAFKA_NAMES.client("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KAFKA_NAMES.client(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 构造参数不能为null() {
        assertThatThrownBy(() -> new KafkaNames(null)).isInstanceOf(NullPointerException.class);
    }
}
