package com.game.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.api.ClientMessageService;
import com.game.api.proto.GateNodeInfo;
import com.game.common.RunMode;
import com.game.common.token.GateTokens;
import com.game.contract.MessageIdRegistry;
import com.game.gate.metrics.GateMetrics;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * gate 写进节点目录的条目（批次 5.4 先行件加了 {@code started_at_ms}）。只对着组条目的函数与构造器断言，不起 gate、不连 Redis。
 */
class GateNodeDirectoryEntryTest {

    @Test
    void 目录条目_逐字段_带进程启动时刻_不标排空() throws Exception {
        GateNodeInfo info = GateNode.directoryEntry(2, 7, "instance-a", "10.0.0.7", 11010, 42, 1_800_000_000_123L);

        assertThat(info).isEqualTo(GateNodeInfo.newBuilder().setZoneId(2).setNodeId(7).setInstanceId("instance-a")
                .setClientHost("10.0.0.7").setClientPort(11010).setPlayerCount(42).setStartedAtMs(1_800_000_000_123L).build());
        assertThat(info.getStartedAtMs()).isEqualTo(1_800_000_000_123L);
        assertThat(info.getDraining()).as("gate 自己从不标排空").isFalse();
        // 线上形态：8 号字段、varint。旧版本读方不认识它也能解析（当未知字段跳过），旧条目读出来是 0
        GateNodeInfo reparsed = GateNodeInfo.parseFrom(info.toByteArray());
        assertThat(reparsed.getStartedAtMs()).isEqualTo(1_800_000_000_123L);
        assertThat(GateNodeInfo.getDescriptor().findFieldByName("started_at_ms").getNumber()).isEqualTo(8);
        assertThat(GateNodeInfo.parseFrom(info.toBuilder().clearStartedAtMs().build().toByteArray()).getStartedAtMs())
                .as("没写这个字段的旧条目 = 0（最旧）").isZero();
    }

    @Test
    void 同一个进程每次刷新写的是同一个启动时刻_人数照常变化() {
        GateNodeInfo first = GateNode.directoryEntry(1, 1, "i", "127.0.0.1", 11000, 0, 1_800_000_000_000L);
        GateNodeInfo later = GateNode.directoryEntry(1, 1, "i", "127.0.0.1", 11000, 9, 1_800_000_000_000L);

        assertThat(later.getStartedAtMs()).isEqualTo(first.getStartedAtMs());
        assertThat(later.getPlayerCount()).isEqualTo(9);
    }

    /** 启动时刻在构造时取定一次（墙钟），之后不变：落在构造前后两次读钟之间，且重复读取得到同一个值。 */
    @Test
    void 启动时刻在构造时取定一次_之后不变() {
        long before = System.currentTimeMillis();
        GateNode node = new GateNode(mock(RedissonClient.class), MessageIdRegistry.loadFromClasspath(),
                GateTokens.ofUtf8("gate-node-directory-entry-test"), null, mock(ClientMessageService.class),
                Map.of(), new GateProperties(null, null, null, null, null, null, null, null, null, null, null, null), 1,
                "127.0.0.1", Path.of("config-data/tables"), GateMetrics.noop(), RunMode.PROD);
        long after = System.currentTimeMillis();

        long startedAt = node.startedAtMs();

        assertThat(startedAt).isBetween(before, after);
        assertThat(node.startedAtMs()).isEqualTo(startedAt);
        assertThat(startedAt).as("非 0：0 留给不认识这个字段的旧 gate").isPositive();
    }
}
