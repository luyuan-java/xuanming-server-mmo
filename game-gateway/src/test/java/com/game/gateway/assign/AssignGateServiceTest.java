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
}
