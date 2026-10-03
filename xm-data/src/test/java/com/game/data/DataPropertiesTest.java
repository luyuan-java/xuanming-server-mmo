package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 走 Spring Boot 真实的构造器绑定（缺省值、各 topic 各自的消费者缺省、校验）。 */
class DataPropertiesTest {

    private static DataProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm.data", DataProperties.class);
    }

    @Test
    void 什么都不配_各消费者取各自的缺省() {
        DataProperties p = bind(Map.of());

        assertThat(p.transactionLog().group()).isEqualTo("xm-data-transaction-log");
        assertThat(p.transactionLog().maxPollRecords()).isEqualTo(500);
        assertThat(p.playerSnapshot().group()).isEqualTo("xm-data-player-snapshot");
        assertThat(p.playerSnapshot().maxPollRecords()).isEqualTo(50);
        assertThat(p.playerSnapshot().insertChunk()).isEqualTo(10);
        assertThat(p.retention().transactionLog()).isZero();
        assertThat(p.retention().playerSnapshot()).isZero();
    }

    @Test
    void 可以单独覆盖_非法值拒绝() {
        DataProperties p = bind(Map.of("xm.data.player-snapshot.insert-chunk", "3",
                "xm.data.retention.player-snapshot", "30d"));
        assertThat(p.playerSnapshot().insertChunk()).isEqualTo(3);
        assertThat(p.playerSnapshot().group()).isEqualTo("xm-data-player-snapshot");
        assertThat(p.retention().playerSnapshot()).isEqualTo(Duration.ofDays(30));

        assertThatThrownBy(() -> bind(Map.of("xm.data.player-snapshot.max-poll-records", "0")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.transaction-log.group", " ")))
                .isInstanceOf(BindException.class);
    }
}
