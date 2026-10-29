package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.consume.AuditGroupProbe;
import com.game.data.consume.AuditOffsetSnapshot;
import com.game.data.consume.AuditProbeState;
import com.game.data.metrics.DataMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class AuditProbeAdminControllerTest {
    @Test void 只凭正确请求头直接调handler仍被拒绝() {
        var request = new MockHttpServletRequest("GET", AuditProbeAdminController.PATH);
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "secret");
        request.addHeader(AdminAuthFilter.OPERATOR_HEADER, "ops");
        var probe = mock(AuditGroupProbe.class);
        assertThatThrownBy(() -> new AuditProbeAdminController(probe).view(request))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(probe);
    }

    @Test void 经鉴权只查内存原件_大offset以十进制字符串返回() throws Exception {
        var target = new AuditOffsetSnapshot.Target(AuditOffsetSnapshot.ConsumerName.TRANSACTION_LOG,
                "probe-test-g1", 1, "probe-group", 1);
        long end = 9_007_199_254_740_993L;
        var sample = new AuditOffsetSnapshot(target, "topic-id", List.of(
                new AuditOffsetSnapshot.Partition(0, 0, 0L, end, end, 0L)), end, end, 0, 0);
        var view = new AuditProbeState.View(target, AuditProbeState.Status.STALE, null, 100, 10, 10, sample, null, 0);
        var probe = mock(AuditGroupProbe.class);
        when(probe.views()).thenReturn(List.of(view));
        var controller = new AuditProbeAdminController(probe);
        var request = new MockHttpServletRequest("GET", AuditProbeAdminController.PATH);
        request.setServletPath(AuditProbeAdminController.PATH);
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "secret");
        request.addHeader(AdminAuthFilter.OPERATOR_HEADER, "ops");
        AtomicReference<List<AuditProbeAdminController.ProbeView>> result = new AtomicReference<>();
        try (var registry = new SimpleMeterRegistry()) {
            new AdminAuthFilter("secret", new DataMetrics(registry)).doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> result.set(controller.view((HttpServletRequest) req)));
            String json = new ObjectMapper().writeValueAsString(result.get());
            assertThat(json).contains("\"status\":\"STALE\"", "\"end\":\"9007199254740993\"");
            assertThat(json).doesNotContain("secret");
            assertThat(registry.get("game.data.admin.requests").tag("op", "audit_consumer_probes").counter().count()).isEqualTo(1);
        }
        verify(probe).views();
        verifyNoMoreInteractions(probe);
    }
}
