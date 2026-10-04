package com.game.gateway.queue;

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

import com.game.api.proto.GateNodeInfo;
import com.game.gateway.gate.GateSource;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 放行循环：只有主放行；预算 = 容量 − 在线；按段放、段间把已分人数算进 gate 负载；只放开放的区；出错（含 Error）不抛。存储是替身。 */
class QueueDispatcherTest {

    private static GateNodeInfo gate(int node, int players) {
        return GateNodeInfo.newBuilder().setZoneId(1).setNodeId(node).setInstanceId("g" + node)
                .setClientHost("10.0.0." + node).setClientPort(11000 + node).setPlayerCount(players).build();
    }

    private static ZoneDirectory zones(int capacity) {
        return new ZoneDirectory(() -> List.of(
                new ZoneRow(1, "一区", 0, capacity, "", null, true, 1, 0, 0),
                new ZoneRow(2, "二区", 1, capacity, "维护", null, false, 2, 0, 0)), System::nanoTime);
    }

    private final LoginQueue queue = mock(LoginQueue.class);
    private final GateSource gates = mock(GateSource.class);
    private final AtomicLong admits = new AtomicLong();

    private QueueDispatcher dispatcher(int capacity, QueueDispatcher.Leadership leadership) {
        return new QueueDispatcher(queue, new QueueCapacity(1.5), zones(capacity), gates, leadership, admits::addAndGet);
    }

    @Test
    void 预算等于容量减在线_弹出不足一段就停() {
        when(queue.queueLength(1)).thenReturn(5L);
        when(gates.listGates(1)).thenReturn(List.of(gate(1, 4), gate(2, 3)));
        when(queue.dispatch(eq(1), anyLong(), anyInt(), any())).thenReturn(new LoginQueue.Dispatched(2, 1));
        QueueDispatcher dispatcher = dispatcher(10, QueueDispatcher.always());

        dispatcher.tick();

        verify(queue, times(1)).dispatch(1, 3, QueueDispatcher.CHUNK, gate(2, 3));
        assertThat(admits.get()).as("过期条目不计").isEqualTo(2);
        verify(queue, never()).queueLength(2);
        assertThat(dispatcher.isLeader()).isTrue();
    }

    @Test
    void 一轮按段摊到各gate_已分人数算进负载() {
        when(queue.queueLength(1)).thenReturn(120L);
        when(gates.listGates(1)).thenReturn(List.of(gate(2, 0), gate(1, 0)));
        when(queue.dispatch(eq(1), anyLong(), anyInt(), any())).thenReturn(
                new LoginQueue.Dispatched(50, 0), new LoginQueue.Dispatched(48, 2), new LoginQueue.Dispatched(20, 0));
        dispatcher(1000, QueueDispatcher.always()).tick();

        ArgumentCaptor<GateNodeInfo> picked = ArgumentCaptor.forClass(GateNodeInfo.class);
        verify(queue, times(3)).dispatch(eq(1), eq(1000L), eq(QueueDispatcher.CHUNK), picked.capture());
        assertThat(picked.getAllValues()).extracting(GateNodeInfo::getNodeId)
                .as("平局取节点号小的；第二段 gate1 已分 50；第三段 gate2 已分 48 < 50").containsExactly(1, 2, 2);
        assertThat(admits.get()).isEqualTo(118);
    }

    @Test
    void 没预算或没gate不放行() {
        when(queue.queueLength(1)).thenReturn(5L);
        when(gates.listGates(1)).thenReturn(List.of(gate(1, 10)));
        QueueDispatcher dispatcher = dispatcher(10, QueueDispatcher.always());
        dispatcher.tick();
        when(gates.listGates(1)).thenReturn(List.of());
        dispatcher.tick();
        verify(queue, never()).dispatch(anyInt(), anyLong(), anyInt(), any());
    }

    @Test
    void 不是主什么都不碰_出错不抛_Error也接住() {
        AtomicBoolean lead = new AtomicBoolean(false);
        QueueDispatcher dispatcher = dispatcher(10, QueueDispatcher.when(lead::get));
        dispatcher.tick();
        verify(queue, never()).queueLength(anyInt());

        lead.set(true);
        when(queue.queueLength(1)).thenThrow(new IllegalStateException("Redis 不可达"));
        dispatcher.tick();
        assertThat(dispatcher.isLeader()).isTrue();
        verify(queue, never()).dispatch(anyInt(), anyLong(), anyInt(), any());

        QueueDispatcher broken = dispatcher(10, new QueueDispatcher.Leadership() {
            @Override
            public boolean holdOrAcquire() {
                throw new OutOfMemoryError("测试");
            }

            @Override
            public void release() {
            }
        });
        broken.tick();
        broken.tick();
    }
}
