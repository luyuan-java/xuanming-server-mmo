package com.game.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.trace.OtlpHttpSpanExporter;
import com.game.common.trace.TraceContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 覆盖 2026-11-19 网关 HTTP 入口不采信公网外部 traceparent、不回显 trace_id 给客户端。 */
class GatewayTraceFilterTest {

    @Test
    void 公网api入口忽略外部traceparent并开新根_管理入口接续traceparent_响应不泄漏traceId() throws Exception {
        GatewayTraceFilter filter = new GatewayTraceFilter();
        String forgedExternal = "00-11111111111111111111111111111111-2222222222222222-01";
        List<String> exported = new ArrayList<>();
        try (OtlpHttpSpanExporter exporter = new OtlpHttpSpanExporter(
                "game-gateway", URI.create("http://127.0.0.1:4318/v1/traces"),
                16, 16, 60_000L, Duration.ofSeconds(5),
                (u, body, t) -> {
                    exported.add(new String(body, StandardCharsets.UTF_8));
                    return true;
                })) {
            OtlpHttpSpanExporter.setGlobal(exporter);

            // 1. 公网 /api/login 忽略客户端伪造 traceparent，新开根且响应不回显 traceparent / trace_id
            MockHttpServletRequest pubReq = new MockHttpServletRequest("POST", "/api/login");
            pubReq.addHeader(TraceContext.TRACEPARENT_HEADER, forgedExternal);
            MockHttpServletResponse pubResp = new MockHttpServletResponse();
            AtomicReference<String> pubTrace = new AtomicReference<>();
            filter.doFilter(pubReq, pubResp, (req, resp) -> pubTrace.set(MDC.get(TraceContext.MDC_TRACE_ID)));
            assertThat(pubTrace.get()).isNotNull().isNotEqualTo("11111111111111111111111111111111");
            assertThat(pubResp.getHeader(TraceContext.TRACEPARENT_HEADER)).isNull();
            assertThat(pubResp.getHeader("X-Trace-Id")).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();

            // 2. 内网 /admin/killswitch 接续上游 traceparent
            MockHttpServletRequest adminReq = new MockHttpServletRequest("GET", "/admin/killswitch");
            adminReq.addHeader(TraceContext.TRACEPARENT_HEADER, forgedExternal);
            MockHttpServletResponse adminResp = new MockHttpServletResponse();
            AtomicReference<String> adminTrace = new AtomicReference<>();
            filter.doFilter(adminReq, adminResp, (req, resp) -> adminTrace.set(MDC.get(TraceContext.MDC_TRACE_ID)));
            assertThat(adminTrace.get()).isEqualTo("11111111111111111111111111111111");

            assertThat(exporter.flushOnce()).isEqualTo(2);
            assertThat(exported.getFirst()).contains("gateway/POST /api/login");
        } finally {
            OtlpHttpSpanExporter.setGlobal(null);
        }
    }
}
