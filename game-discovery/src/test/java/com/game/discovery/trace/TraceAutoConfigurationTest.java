package com.game.discovery.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.trace.ErrorReportBuffer;
import com.game.common.trace.OtlpHttpSpanExporter;
import com.game.common.trace.TraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证错误汇总缓冲与 OTLP 导出器的 Micrometer 指标名与口径。 */
class TraceAutoConfigurationTest {

    @Test
    void 绑定错误汇总与OTLP导出指标且口径精确() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ErrorReportBuffer buffer = new ErrorReportBuffer(1);
        TraceAutoConfiguration.bindErrorReportMeters(registry, buffer);

        TraceContext ctx = TraceContext.newRoot(777L);
        try (TraceContext.Scope ignored = ctx.attach()) {
            buffer.record("db", "deadlock");
            buffer.record("db", "dropped");
        }
        assertThat(registry.get("error_reporter_events_total").functionCounter().count()).isEqualTo(2.0);
        assertThat(registry.get("error_reporter_dropped_events_total").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("error_reporter_buffered_events").gauge().value()).isEqualTo(1.0);

        try (OtlpHttpSpanExporter exporter = new OtlpHttpSpanExporter(
                "game-match", URI.create("http://127.0.0.1:4318/v1/traces"),
                8, 8, 5000L, Duration.ofSeconds(5), (u, b, t) -> true)) {
            TraceAutoConfiguration.bindExporterMeters(registry, exporter);
            exporter.offer(new OtlpHttpSpanExporter.SpanRecord(
                    ctx.traceId(), ctx.spanId(), "", "match/JoinQueue",
                    OtlpHttpSpanExporter.SpanKind.SERVER, 100L, 200L, false, "", Map.of()));
            exporter.flushOnce();
            assertThat(registry.get("otlp_spans_exported_total").functionCounter().count()).isEqualTo(1.0);
            assertThat(registry.get("otlp_spans_dropped_total").functionCounter().count()).isEqualTo(0.0);
        }
    }
}
