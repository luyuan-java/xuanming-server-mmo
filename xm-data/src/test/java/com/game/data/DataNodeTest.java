package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.audit.AuditProperties;
import com.game.data.metrics.DataMetrics;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 保留期清理按表各自生效、表与 Mapper 不串、一张表失败不影响另一张；停止干净（Kafka 不可达时不需要 broker）。 */
class DataNodeTest {

    private static final long NOW = 1_800_000_000_000L;

    private final TransactionLogMapper transactionLog = mock(TransactionLogMapper.class);
    private final PlayerSnapshotMapper playerSnapshot = mock(PlayerSnapshotMapper.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DataNode node;

    private DataNode node(String txlogRetention, String snapshotRetention) {
        DataProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "xm.data.retention.transaction-log", txlogRetention,
                "xm.data.retention.player-snapshot", snapshotRetention,
                "xm.data.retention.batch", "2"))).bindOrCreate("xm.data", DataProperties.class);
        AuditProperties audit = new AuditProperties(true, "127.0.0.1:1", 1, (short) 1, Duration.ofMillis(10));
        node = new DataNode(audit, props, transactionLog, playerSnapshot, null, new DataMetrics(meters), () -> {
            throw new IllegalStateException("Kafka 不可达");
        }, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        return node;
    }

    @AfterEach
    void stop() {
        if (node != null) {
            node.stop();
        }
    }

    @Test
    void 保留期为0的表不清理_另一张按自己的保留期清理() {
        node("0s", "30d").purgeExpired();

        verify(transactionLog, never()).deleteOlderThan(anyLong(), anyInt());
        verify(playerSnapshot).deleteOlderThan(NOW - Duration.ofDays(30).toMillis(), 2);

        node("7d", "0s").purgeExpired();

        verify(transactionLog).deleteOlderThan(NOW - Duration.ofDays(7).toMillis(), 2);
        verify(playerSnapshot, times(1)).deleteOlderThan(anyLong(), anyInt());
    }

    @Test
    void 运行中删满一批就继续删_一张表失败不影响另一张_计数按表() {
        when(transactionLog.deleteOlderThan(anyLong(), anyInt())).thenThrow(new IllegalStateException("库不可达"));
        when(playerSnapshot.deleteOlderThan(anyLong(), anyInt())).thenReturn(2, 2, 1);
        DataNode n = node("7d", "30d");
        n.start();

        n.purgeExpired();

        verify(playerSnapshot, times(3)).deleteOlderThan(anyLong(), anyInt());
        assertThat(meters.get("xm.data.retention.deleted").tag("table", "player_snapshot").counter().count())
                .isEqualTo(5);
    }

    @Test
    void Kafka不可达也能启动_停止及时返回() {
        DataNode n = node("0s", "0s");
        n.start();
        assertThat(n.isRunning()).isTrue();

        long started = System.nanoTime();
        n.stop();

        assertThat(n.isRunning()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
