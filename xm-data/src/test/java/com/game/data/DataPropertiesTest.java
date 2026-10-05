package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 走 Spring Boot 真实的构造器绑定（缺省值、各 topic 各自的消费者缺省、校验、保留期启动约束 T-P3）。 */
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
        assertThat(p.retention().gmSnapshot()).as("Q11：代码缺省永久").isZero();
        assertThat(p.ops().maxWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(p.recall().maxRows()).isEqualTo(10_000);
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
        assertThatThrownBy(() -> bind(Map.of("xm.data.ops.max-window", "0s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.recall.max-rows", "0"))).isInstanceOf(BindException.class);
    }

    @Test
    void 保留期约束_流水为0或不短于快照且快照非0_GM快照不受约束() {
        // Q11 推荐的生产值：流水 180 天、上下线快照 90 天、GM / 安全快照永久
        DataProperties p = bind(Map.of("xm.data.retention.transaction-log", "180d",
                "xm.data.retention.player-snapshot", "90d"));
        assertThat(p.retention().gmSnapshot()).isZero();
        assertThat(bind(Map.of("xm.data.retention.transaction-log", "90d", "xm.data.retention.player-snapshot", "90d",
                "xm.data.retention.gm-snapshot", "30d")).retention().gmSnapshot()).isEqualTo(Duration.ofDays(30));
        assertThat(bind(Map.of("xm.data.retention.player-snapshot", "90d")).retention().transactionLog())
                .as("流水永久保留时快照随便设").isZero();

        assertThatThrownBy(() -> bind(Map.of("xm.data.retention.transaction-log", "30d",
                "xm.data.retention.player-snapshot", "90d"))).as("流水比快照短：回档窗口内解释不了差异")
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.retention.transaction-log", "30d")))
                .as("流水有限而快照永久").isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.retention.gm-snapshot", "-1s"))).isInstanceOf(BindException.class);
    }
}
