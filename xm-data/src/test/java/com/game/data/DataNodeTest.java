package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.audit.AuditProperties;
import com.game.data.metrics.DataMetrics;
import com.game.data.snapshot.SnapshotCauses;
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

/**
 * 保留期清理按表 / 按快照原因类各自生效、表与 Mapper 不串、一张表失败不影响另一张（T-P3：GM / 安全快照不随上下线快照的保留期删）；
 * 停止干净（Kafka 不可达时不需要 broker）。
 */
class DataNodeTest {

    private static final long NOW = 1_800_000_000_000L;

    private final TransactionLogMapper transactionLog = mock(TransactionLogMapper.class);
    private final PlayerSnapshotMapper playerSnapshot = mock(PlayerSnapshotMapper.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DataNode node;

    private DataNode node(String txlogRetention, String snapshotRetention, String gmRetention) {
        DataProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "xm.data.retention.transaction-log", txlogRetention,
                "xm.data.retention.player-snapshot", snapshotRetention,
                "xm.data.retention.gm-snapshot", gmRetention,
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
    void 保留期为0的不清理_快照按原因分两类各按自己的保留期() {
        node("0s", "30d", "0s").purgeExpired();

        verify(transactionLog, never()).deleteOlderThan(anyLong(), anyInt());
        verify(playerSnapshot).deleteOlderThan(NOW - Duration.ofDays(30).toMillis(), SnapshotCauses.ROUTINE_RETENTION, 2);
        verify(playerSnapshot, never()).deleteOlderThan(anyLong(), eq(SnapshotCauses.GM_RETENTION), anyInt());

        node("90d", "60d", "400d").purgeExpired();

        verify(transactionLog).deleteOlderThan(NOW - Duration.ofDays(90).toMillis(), 2);
        verify(playerSnapshot).deleteOlderThan(NOW - Duration.ofDays(60).toMillis(), SnapshotCauses.ROUTINE_RETENTION, 2);
        verify(playerSnapshot).deleteOlderThan(NOW - Duration.ofDays(400).toMillis(), SnapshotCauses.GM_RETENTION, 2);
    }

    @Test
    void 两类原因名单不重叠_不认识的与PRE_TRADE都不在名单里() {
        assertThat(SnapshotCauses.ROUTINE_RETENTION).doesNotContainAnyElementsOf(SnapshotCauses.GM_RETENTION)
                .containsExactlyInAnyOrder(SnapshotCauses.LOGIN, SnapshotCauses.LOGOUT, SnapshotCauses.PERIODIC);
        assertThat(SnapshotCauses.GM_RETENTION).contains(SnapshotCauses.PRE_ROLLBACK, SnapshotCauses.PRE_GM_EDIT,
                SnapshotCauses.GM_MANUAL, SnapshotCauses.PRE_MAINTENANCE);
        assertThat(SnapshotCauses.ROUTINE_RETENTION).doesNotContain(SnapshotCauses.PRE_TRADE);
        assertThat(SnapshotCauses.GM_RETENTION).doesNotContain(SnapshotCauses.PRE_TRADE);
    }

    @Test
    void 运行中删满一批就继续删_一张表失败不影响另一张_计数按表() {
        when(transactionLog.deleteOlderThan(anyLong(), anyInt())).thenThrow(new IllegalStateException("库不可达"));
        when(playerSnapshot.deleteOlderThan(anyLong(), eq(SnapshotCauses.ROUTINE_RETENTION), anyInt()))
                .thenReturn(2, 2, 1);
        when(playerSnapshot.deleteOlderThan(anyLong(), eq(SnapshotCauses.GM_RETENTION), anyInt())).thenReturn(1);
        DataNode n = node("90d", "30d", "400d");
        n.start();

        n.purgeExpired();

        verify(playerSnapshot, times(4)).deleteOlderThan(anyLong(), any(), anyInt());
        assertThat(meters.get("xm.data.retention.deleted").tag("table", "player_snapshot").counter().count())
                .isEqualTo(5);
        assertThat(meters.get("xm.data.retention.deleted").tag("table", "player_snapshot_gm").counter().count())
                .isEqualTo(1);
    }

    @Test
    void Kafka不可达也能启动_停止及时返回() {
        DataNode n = node("0s", "0s", "0s");
        n.start();
        assertThat(n.isRunning()).isTrue();

        long started = System.nanoTime();
        n.stop();

        assertThat(n.isRunning()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
