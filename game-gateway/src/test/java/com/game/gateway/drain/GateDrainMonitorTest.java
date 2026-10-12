package com.game.gateway.drain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.drain.GateDrainMarks;
import com.game.discovery.drain.GateDrainMarks.Mark;
import com.game.gateway.store.ZoneRow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * gate 排空判定：阈值相等算排空、deadline 0 永不超时放行、时钟回拨按刚打上算；一轮里写 drained（带判定时那一份标记）、
 * 清残留、理由没变不重复写、不再满足时撤掉 drained、旧实例的标记比较并删除。存储是替身。
 */
class GateDrainMonitorTest {

    private static final GateDrainSettings POLICY = new GateDrainSettings(0L, Duration.ofSeconds(100), Duration.ofSeconds(5));
    private static final ZoneRow ZONE = new ZoneRow(1, "一区", 0, 100, "", null, true, 1, 0, 0);

    private static GateNodeInfo gate(int node, int players) {
        return GateNodeInfo.newBuilder().setZoneId(1).setNodeId(node).setInstanceId("i" + node).setPlayerCount(players).build();
    }

    private static Mark mark(long since, String instance) {
        return new Mark(since, instance, since + ":" + instance);
    }

    @Test
    void 判定_阈值_deadline_时钟回拨() {
        assertThat(GateDrainMonitor.evaluate(0, 1000, 1001, POLICY).reason()).isEqualTo("below_threshold");
        GateDrainSettings three = new GateDrainSettings(3L, Duration.ZERO, Duration.ofSeconds(5));
        assertThat(GateDrainMonitor.evaluate(3, 1000, 1001, three).drained()).as("等于阈值算排空").isTrue();
        assertThat(GateDrainMonitor.evaluate(4, 1000, 1_000_000, three).drained()).as("deadline 0 永不超时放行").isFalse();
        assertThat(GateDrainMonitor.evaluate(5, 1000, 1099, POLICY).drained()).isFalse();
        GateDrainMonitor.Verdict deadline = GateDrainMonitor.evaluate(5, 1000, 1100, POLICY);
        assertThat(deadline.reason()).isEqualTo("deadline");
        assertThat(deadline.waitedSec()).isEqualTo(100);
        assertThat(GateDrainMonitor.evaluate(5, 2000, 1000, POLICY).waitedSec()).as("打标记时刻在将来按刚打上算").isZero();
    }

    @Test
    void 一轮_写drained_清残留_不重复写_不再满足撤掉_旧实例标记比较并删除() {
        GateDrainMarks marks = mock(GateDrainMarks.class);
        Mark m1 = mark(900, "i1");
        Mark old6 = mark(900, "dead-instance");
        when(marks.draining(eq(1), any())).thenReturn(Map.of(1, m1, 2, mark(900, "i2"), 4, mark(900, "i4"),
                5, mark(900, "i5"), 6, old6));
        when(marks.drained(eq(1), any())).thenReturn(Map.of(2, "below_threshold", 3, "deadline", 5, "below_threshold"));
        when(marks.markDrained(anyInt(), anyInt(), any(), anyString())).thenReturn(GateDrainMarks.DrainedWrite.WRITTEN);
        when(marks.clearIf(eq(1), eq(6), any())).thenReturn(true);
        GateDrainMonitor monitor = new GateDrainMonitor(() -> List.of(ZONE),
                zone -> List.of(gate(1, 0), gate(2, 0), gate(3, 7), gate(4, 7), gate(5, 7), gate(6, 0)), marks, POLICY,
                () -> 950L);

        monitor.tick();

        verify(marks).markDrained(1, 1, m1, "below_threshold");
        verify(marks, never()).markDrained(eq(1), eq(2), any(), anyString());
        verify(marks).clearDrained(1, 3);
        verify(marks, never()).markDrained(eq(1), eq(4), any(), anyString());
        verify(marks).clearDrained(1, 5);
        verify(marks).clearIf(1, 6, old6);
        verify(marks, never()).markDrained(eq(1), eq(6), any(), anyString());
        verify(marks, never()).clearDrained(1, 1);
    }

    @Test
    void 存储出错不抛() {
        GateDrainMarks marks = mock(GateDrainMarks.class);
        when(marks.draining(anyInt(), any())).thenThrow(new IllegalStateException("Redis 不可达"));
        new GateDrainMonitor(() -> List.of(ZONE), zone -> List.of(gate(1, 0)), marks, POLICY, () -> 950L).tick();
        new GateDrainMonitor(() -> {
            throw new OutOfMemoryError("测试");
        }, zone -> List.of(), marks, POLICY, () -> 950L).tick();
    }
}
