package com.game.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** 覆盖 2026-11-12/19 分布式链路追踪：W3C traceparent、MDC、异步续接、错误汇总缓冲与 OTLP/HTTP JSON 导出。 */
class TraceContextAndOtlpTest {

    @Test
    void W3C_traceparent解析校验全零与大小写_MDC三键准确写入与还原_异步回调续接上下文() {
        // 全零 / 大写 / 长度错误 / 版本 ff 均拒绝
        assertThat(TraceContext.parseTraceparent("00-00000000000000000000000000000000-0123456789abcdef-01", 0)).isNull();
        assertThat(TraceContext.parseTraceparent("00-0123456789abcdef0123456789abcdef-0000000000000000-01", 0)).isNull();
        assertThat(TraceContext.parseTraceparent("00-0123456789ABCDEF0123456789abcdef-0123456789abcdef-01", 0)).isNull();
        assertThat(TraceContext.parseTraceparent("ff-0123456789abcdef0123456789abcdef-0123456789abcdef-01", 0)).isNull();

        String incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContext child = TraceContext.continueOrRoot(incoming, 10086L);
        assertThat(child.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(child.parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(child.spanId()).isNotEqualTo("00f067aa0ba902b7");
        assertThat(child.toTraceparent()).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-").endsWith("-01");

        AtomicReference<String> asyncTrace = new AtomicReference<>();
        AtomicReference<String> asyncPlayer = new AtomicReference<>();
        Runnable captured;
        try (TraceContext.Scope ignored = child.attach()) {
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(MDC.get(TraceContext.MDC_SPAN_ID)).isEqualTo(child.spanId());
            assertThat(MDC.get(TraceContext.MDC_PLAYER_ID)).isEqualTo("10086");
            captured = TraceContext.wrap(() -> {
                asyncTrace.set(MDC.get(TraceContext.MDC_TRACE_ID));
                asyncPlayer.set(MDC.get(TraceContext.MDC_PLAYER_ID));
            });
        }
        // 退出作用域后 MDC 干净还原
        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_SPAN_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_PLAYER_ID)).isNull();

        // 异步回调执行时恢复 trace_id 与 player_id，结束后再还原
        captured.run();
        assertThat(asyncTrace.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(asyncPlayer.get()).isEqualTo("10086");
        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
    }

    @Test
    void OTLP_HTTP批量导出器按JSON协议发送_失败丢批不重试_错误汇总缓冲附带trace与playerId() {
        List<String> posted = new ArrayList<>();
        try (OtlpHttpSpanExporter exporter = new OtlpHttpSpanExporter(
                "game-trade", URI.create("http://127.0.0.1:4318/v1/traces"),
                2, 512, 5000L, Duration.ofSeconds(30),
                (uri, body, timeout) -> {
                    posted.add(new String(body, StandardCharsets.UTF_8));
                    return posted.size() == 1; // 第一次成功，第二次失败丢批
                })) {
            TraceContext root = TraceContext.newRoot(42L);
            OtlpHttpSpanExporter.SpanRecord s1 = new OtlpHttpSpanExporter.SpanRecord(
                    root.traceId(), root.spanId(), "", "trade/GetMyShelf",
                    OtlpHttpSpanExporter.SpanKind.SERVER, 1000L, 2000L, false, "", Map.of("rpc.system", "dubbo"));
            assertThat(exporter.offer(s1)).isTrue();
            assertThat(exporter.offer(s1)).isTrue();
            assertThat(exporter.offer(s1)).isFalse(); // 队列容量 2，第 3 条丢弃计数
            assertThat(exporter.droppedSpans()).isEqualTo(1L);

            assertThat(exporter.flushOnce()).isEqualTo(2);
            assertThat(exporter.exportedSpans()).isEqualTo(2L);
            assertThat(posted.getFirst())
                    .contains("\"service.name\"")
                    .contains("\"game-trade\"")
                    .contains("\"trade/GetMyShelf\"")
                    .contains(root.traceId());

            // 失败丢批不重试
            assertThat(exporter.offer(s1)).isTrue();
            assertThat(exporter.flushOnce()).isZero();
            assertThat(exporter.failedBatches()).isEqualTo(1L);
            assertThat(exporter.droppedSpans()).isEqualTo(2L);
        }

        // 错误汇总缓冲自动捕获当前 MDC / TraceContext
        ErrorReportBuffer buffer = new ErrorReportBuffer(1);
        TraceContext ctx = TraceContext.newRoot(9001L);
        try (TraceContext.Scope ignored = ctx.attach()) {
            assertThat(buffer.record("rpc", "timeout")).isTrue();
            assertThat(buffer.record("rpc", "overflow")).isFalse();
        }
        assertThat(buffer.totalEvents()).isEqualTo(2L);
        assertThat(buffer.droppedEvents()).isEqualTo(1L);
        assertThat(buffer.bufferedEvents()).isEqualTo(1);
        var drained = buffer.drain(10);
        assertThat(drained).hasSize(1);
        assertThat(drained.getFirst().traceId()).isEqualTo(ctx.traceId());
        assertThat(drained.getFirst().playerId()).isEqualTo(9001L);
    }
}
