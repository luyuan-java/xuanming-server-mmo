package com.game.friend.sweep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.friend.store.SweepStore;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class FriendSweepTest {

    private final SweepStore store = mock(SweepStore.class);
    private final Map<String, Long> pending = new HashMap<>();
    private final Map<String, Long> idle = new HashMap<>();
    private final FriendSweep.Gauges gauges = new FriendSweep.Gauges() {
        @Override
        public void pendingRows(String mode, long value) {
            pending.put(mode, value);
        }

        @Override
        public void idleCapacityRows(String mode, long value) {
            idle.put(mode, value);
        }
    };

    private FriendSweep sweep(String mode) {
        return new FriendSweep(store, mode, Duration.ofMinutes(5), 7, 1000, () -> 123_456L, gauges);
    }

    private static SweepStore.Result result(long seen, long deleted, Exception error) {
        return new SweepStore.Result(seen, deleted, error);
    }

    @Test
    void 前一段失败不影响后一段_失败段不刷Gauge_参数原样传下去() {
        when(store.sweepTerminalRequests(any(), anyInt(), anyInt(), anyLong(), any()))
                .thenReturn(result(0, 0, new SQLException("down")));
        when(store.sweepIdleCapacityRows(any(), anyInt(), anyInt(), anyLong(), any())).thenReturn(result(3, 2, null));
        sweep("delete").runRound();
        assertThat(pending).isEmpty();
        assertThat(idle).containsEntry("delete", 3L);
        verify(store).sweepTerminalRequests(eq("delete"), eq(7), eq(1000), eq(123_456L), any(BooleanSupplier.class));
        verify(store).sweepIdleCapacityRows(eq("delete"), eq(7), eq(1000), eq(123_456L), any(BooleanSupplier.class));
    }

    @Test
    void 后一段失败被兜住_合法模式成功时刷Gauge含0() {
        when(store.sweepTerminalRequests(any(), anyInt(), anyInt(), anyLong(), any())).thenReturn(result(0, 0, null));
        when(store.sweepIdleCapacityRows(any(), anyInt(), anyInt(), anyLong(), any()))
                .thenReturn(result(5, 1, new SQLException("down")));
        sweep("report_only").runRound();
        assertThat(pending).containsEntry("report_only", 0L);
        assertThat(idle).isEmpty();
    }

    @Test
    void 未知模式两个Gauge都不刷() {
        when(store.sweepTerminalRequests(any(), anyInt(), anyInt(), anyLong(), any())).thenReturn(result(4, 0, null));
        when(store.sweepIdleCapacityRows(any(), anyInt(), anyInt(), anyLong(), any())).thenReturn(result(4, 0, null));
        sweep("bogus").runRound();
        assertThat(pending).isEmpty();
        assertThat(idle).isEmpty();
    }

    @Test
    void 启动与关闭() {
        FriendSweep s = sweep("report_only");
        s.start();
        s.start(); // 幂等
        s.close();
        s.close();
    }
}
