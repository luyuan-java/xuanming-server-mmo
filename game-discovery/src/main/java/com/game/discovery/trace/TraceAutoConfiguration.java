package com.game.discovery.trace;

import com.game.common.trace.ErrorReportBuffer;
import com.game.common.trace.OtlpHttpSpanExporter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 OTLP/HTTP span 批量导出器与错误汇总缓冲指标：
 * <ul>
 *   <li>环境变量 {@code OTEL_EXPORTER_OTLP_ENDPOINT} 配置时自动启动 {@link OtlpHttpSpanExporter} 并注册为全局导出器；</li>
 *   <li>暴露 {@code error_reporter_events_total}、{@code error_reporter_dropped_events_total}、{@code error_reporter_buffered_events}
 *       以及 {@code otlp_spans_exported_total} / {@code otlp_spans_dropped_total}（全低基数，不含玩家 ID）。</li>
 * </ul>
 */
@AutoConfiguration
public class TraceAutoConfiguration {

    @Bean(destroyMethod = "close")
    public OtlpExporterHandle otlpExporterHandle(
            @Value("${spring.application.name:game-service}") String serviceName,
            ObjectProvider<MeterRegistry> meters) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.installFromEnv(serviceName);
        MeterRegistry registry = meters.getIfAvailable();
        if (exporter != null && registry != null) {
            bindExporterMeters(registry, exporter);
        }
        return new OtlpExporterHandle(exporter);
    }

    @Bean
    public ErrorReportBuffer errorReportBuffer(ObjectProvider<MeterRegistry> meters) {
        ErrorReportBuffer buffer = new ErrorReportBuffer(1024);
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            bindErrorReportMeters(registry, buffer);
        }
        return buffer;
    }

    public static void bindErrorReportMeters(MeterRegistry registry, ErrorReportBuffer buffer) {
        FunctionCounter.builder("error_reporter_events_total", buffer, ErrorReportBuffer::totalEvents)
                .description("错误汇总缓冲累计接收事件数")
                .register(registry);
        FunctionCounter.builder("error_reporter_dropped_events_total", buffer, ErrorReportBuffer::droppedEvents)
                .description("错误汇总缓冲队列满丢弃事件数")
                .register(registry);
        Gauge.builder("error_reporter_buffered_events", buffer, ErrorReportBuffer::bufferedEvents)
                .description("错误汇总缓冲当前积压事件数")
                .register(registry);
    }

    public static void bindExporterMeters(MeterRegistry registry, OtlpHttpSpanExporter exporter) {
        FunctionCounter.builder("otlp_spans_exported_total", exporter, OtlpHttpSpanExporter::exportedSpans)
                .description("OTLP/HTTP 成功导出的 span 总数")
                .register(registry);
        FunctionCounter.builder("otlp_spans_dropped_total", exporter, OtlpHttpSpanExporter::droppedSpans)
                .description("OTLP/HTTP 因队列满或发送失败丢弃的 span 总数")
                .register(registry);
    }

    public static final class OtlpExporterHandle implements AutoCloseable {
        private final OtlpHttpSpanExporter exporter;

        OtlpExporterHandle(OtlpHttpSpanExporter exporter) {
            this.exporter = exporter;
        }

        public OtlpHttpSpanExporter exporter() {
            return exporter;
        }

        @Override
        public void close() {
            if (exporter != null) {
                exporter.close();
            }
        }
    }
}
