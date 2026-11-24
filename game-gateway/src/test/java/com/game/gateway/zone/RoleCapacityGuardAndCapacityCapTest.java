package com.game.gateway.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.GateNodeInfo;
import com.game.gateway.queue.QueueCapacity;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoleCapacityGuardAndCapacityCapTest {

    private static GateNodeInfo gate(int zoneId, int nodeId, int players, int maxConn, boolean draining) {
        return GateNodeInfo.newBuilder()
                .setZoneId(zoneId)
                .setNodeId(nodeId)
                .setInstanceId("inst-" + nodeId)
                .setClientHost("10.0.0." + nodeId)
                .setClientPort(11000 + nodeId)
                .setPlayerCount(players)
                .setMaxConnections(maxConn)
                .setDraining(draining)
                .build();
    }

    @Test
    void 排队区容量按在接客gate九成水位封顶_排空不计_未声明不封顶() {
        QueueCapacity cap = new QueueCapacity(1.5);
        ZoneRow z = new ZoneRow(1, "一区", 0, 45_000, "", null, true, 1, 0, 0);
        // 两台 20000 -> 90% 水位和 = 36000 < 45000，被封顶
        List<GateNodeInfo> twoGates = List.of(
                gate(1, 1, 1000, 20_000, false),
                gate(1, 2, 1000, 20_000, false),
                gate(1, 3, 500, 20_000, true));
        assertThat(cap.effectiveCapacity(z, twoGates)).isEqualTo(36_000L);
        assertThat(cap.budget(z, twoGates)).as("在线数含排空 gate 上的 500 人").isEqualTo(33_500L);

        // 任一在接客 gate 未声明上限（0）-> 不封顶，取目录 45000
        List<GateNodeInfo> unbounded = List.of(
                gate(1, 1, 1000, 20_000, false),
                gate(1, 2, 1000, 0, false));
        assertThat(cap.effectiveCapacity(z, unbounded)).isEqualTo(45_000L);

        // 软上限（capacity=0）同样受 gate 水位封顶
        ZoneRow softZone = new ZoneRow(1, "一区", 0, 0, "", null, true, 1, 0, 0);
        List<GateNodeInfo> tinyGate = List.of(gate(1, 1, 100, 100, false));
        assertThat(cap.effectiveCapacity(softZone, tinyGate)).isEqualTo(90L);
        assertThat(cap.budget(softZone, tinyGate)).isZero();
    }

    @Test
    void 角色上限守卫_只问开着且设上限的区_达线CAS关闭_问不到不关() {
        GatewayStore store = mock(GatewayStore.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ZoneRow activeReached = new ZoneRow(1, "一区", 0, 45_000, "", null, true, 1, false, 100, 0, 0);
        ZoneRow activeUnder = new ZoneRow(2, "二区", 0, 45_000, "", null, false, 2, false, 200, 0, 0);
        ZoneRow alreadyClosed = new ZoneRow(3, "三区", 0, 45_000, "", null, false, 3, true, 50, 0, 0);
        ZoneRow noCap = new ZoneRow(4, "四区", 0, 45_000, "", null, false, 4, false, 0, 0, 0);
        ZoneRow failingZone = new ZoneRow(5, "五区", 0, 45_000, "", null, false, 5, false, 10, 0, 0);

        when(store.closeCreateRoleIfCapacityReached(1, 100)).thenReturn(true);
        AtomicInteger queriedMask = new AtomicInteger();
        RoleCapacityGuard guard = new RoleCapacityGuard(
                () -> List.of(activeReached, activeUnder, alreadyClosed, noCap, failingZone),
                zoneId -> {
                    queriedMask.getAndUpdate(m -> m | (1 << zoneId));
                    if (zoneId == 5) {
                        throw new IllegalStateException("login timeout");
                    }
                    return zoneId == 1 ? 100L : 80L;
                },
                store, meters);

        guard.tick();

        // 只问了 1、2、5；已关的 3 和不设上限的 4 不查询
        assertThat(queriedMask.get()).isEqualTo((1 << 1) | (1 << 2) | (1 << 5));
        verify(store).closeCreateRoleIfCapacityReached(1, 100);
        verify(store, never()).closeCreateRoleIfCapacityReached(2, 200);
        verify(store, never()).closeCreateRoleIfCapacityReached(5, 10);
        verify(store, never()).closeCreateRoleIfCapacityReached(3, 50);
        assertThat(meters.get("game.gateway.role.capacity.check").tag("outcome", "closed").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("game.gateway.role.capacity.check").tag("outcome", "ok").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("game.gateway.role.capacity.check").tag("outcome", "error").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("game.gateway.zone.role.count").tag("zone", "1").gauge().value()).isEqualTo(100.0);
        assertThat(meters.get("game.gateway.zone.role.capacity").tag("zone", "1").gauge().value()).isEqualTo(100.0);
    }

    @Test
    void 角色上限守卫_关停写库异常不抛出不中断后续区检查() {
        GatewayStore store = mock(GatewayStore.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ZoneRow z1 = new ZoneRow(1, "一区", 0, 45_000, "", null, true, 1, false, 100, 0, 0);
        ZoneRow z2 = new ZoneRow(2, "二区", 0, 45_000, "", null, false, 2, false, 100, 0, 0);
        when(store.closeCreateRoleIfCapacityReached(1, 100)).thenThrow(new IllegalStateException("mysql transient"));
        when(store.closeCreateRoleIfCapacityReached(2, 100)).thenReturn(true);
        RoleCapacityGuard guard = new RoleCapacityGuard(() -> List.of(z1, z2), zoneId -> 100L, store, meters);

        guard.tick();

        verify(store).closeCreateRoleIfCapacityReached(1, 100);
        verify(store).closeCreateRoleIfCapacityReached(2, 100);
        assertThat(meters.get("game.gateway.role.capacity.check").tag("outcome", "error").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("game.gateway.role.capacity.check").tag("outcome", "closed").counter().count()).isEqualTo(1.0);
    }
}
