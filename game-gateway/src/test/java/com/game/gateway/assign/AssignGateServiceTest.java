package com.game.gateway.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.common.token.GateTokenIssuer;
import com.game.discovery.gate.GateSource;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 区服准入：目录读不到 fail-closed（500 zone_admission_unavailable，不读 gate 目录）；运维改状态约 1 s 内生效；读失败缓存 1 s。 */
class AssignGateServiceTest {

    private final GateSource gates = mock(GateSource.class);
    private final GateTokenIssuer issuer = mock(GateTokenIssuer.class);

    @Test
    void 区服目录读不到_500_不读gate目录() {
        ZoneDirectory broken = new ZoneDirectory(() -> {
            throw new IllegalStateException("MySQL 不可达");
        }, System::nanoTime);
        AssignGateResponse resp = new AssignGateService(broken, gates, issuer).assign(1);
        assertThat(resp.code()).isEqualTo(500);
        assertThat(resp.error()).isEqualTo("zone_admission_unavailable");
        verifyNoInteractions(gates);
    }

    @Test
    void 运维改状态_缓存过期后生效_读失败缓存1秒内立即失败() {
        AtomicReference<List<ZoneRow>> table = new AtomicReference<>(
                List.of(new ZoneRow(1, "一区", 1, 5000, "维护", null, true, 1, 0, 0)));
        AtomicLong nanos = new AtomicLong();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ZoneDirectory zones = new ZoneDirectory(() -> {
            if (failure.get() != null) {
                throw failure.get();
            }
            return table.get();
        }, nanos::get);
        AssignGateService service = new AssignGateService(zones, gates, issuer);
        assertThat(service.assign(1).error()).isEqualTo("zone_maintenance");

        table.set(List.of(new ZoneRow(1, "一区", 2, 5000, "", null, true, 1, 0, 0)));
        nanos.addAndGet(ZoneDirectory.TTL.toNanos() - 1);
        assertThat(service.assign(1).error()).as("缓存未过期").isEqualTo("zone_maintenance");
        nanos.addAndGet(1);
        assertThat(service.assign(1).error()).isEqualTo("zone_closed");

        failure.set(new IllegalStateException("MySQL 抖动"));
        nanos.addAndGet(ZoneDirectory.TTL.toNanos());
        assertThat(service.assign(1).error()).isEqualTo("zone_admission_unavailable");
        failure.set(null);
        assertThat(service.assign(1).error()).as("失败也缓存 1 s：故障期间立即失败、不排队重读").isEqualTo("zone_admission_unavailable");
        nanos.addAndGet(ZoneDirectory.TTL.toNanos());
        assertThat(service.assign(1).error()).isEqualTo("zone_closed");
    }

    @Test
    void 高位指定区不当缺省_零从令牌取区_错区仍拒绝且Assign零仍未知区() {
        var directory = new ZoneDirectory(() -> List.of(
                new ZoneRow(-1, "最大区", 0, 100, "", null, false, 0, 0, 0),
                new ZoneRow(Integer.MIN_VALUE, "另一高位区", 0, 100, "", null, false, 0, 0, 0)), System::nanoTime);
        var queue = mock(com.game.gateway.queue.LoginQueue.class);
        var tokens = new com.game.gateway.queue.QueueTokens("unit-only-queue-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String id = com.game.gateway.queue.QueueTokens.newQueueId();
        String token = tokens.sign(id, -1, 200);
        org.mockito.Mockito.when(queue.lookup(-1, id)).thenReturn(new com.game.gateway.queue.LoginQueue.Waiting(2, 3));
        var service = new AssignGateService(directory, gates, issuer,
                new AssignGateService.Queueing(queue, null, tokens, 1000),
                java.time.Clock.fixed(java.time.Instant.ofEpochSecond(100), java.time.ZoneOffset.UTC));
        assertThat(service.queueStatus(0, token).code()).isEqualTo(100);
        assertThat(service.queueStatus(-1, token).code()).isEqualTo(100);
        assertThat(service.queueStatus(Integer.MIN_VALUE, token).error()).isEqualTo("queue_token_expired");
        assertThat(service.assign(0).error()).isEqualTo("zone_not_found");
        org.mockito.Mockito.verify(queue, org.mockito.Mockito.times(2)).lookup(-1, id);
        org.mockito.Mockito.verify(queue, org.mockito.Mockito.never()).lookup(org.mockito.ArgumentMatchers.eq(Integer.MIN_VALUE), org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(gates, issuer);
    }
}
