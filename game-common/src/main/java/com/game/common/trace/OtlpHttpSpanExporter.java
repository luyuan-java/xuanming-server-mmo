package com.game.common.trace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 零新增第三方依赖的 OTLP/HTTP JSON span 批量导出器（对齐主仓 {@code docs/design/distributed-tracing.md} §10.3）：
 * <ul>
 *   <li>参数采用 OpenTelemetry BatchSpanProcessor 缺省值：队列容量 2048、单批最多 512、每 5 秒刷新一批、超时 30 秒；</li>
 *   <li>失败丢批并递增丢弃计数，不重试、不阻塞业务线程；</li>
 *   <li>环境变量 {@code OTEL_EXPORTER_OTLP_ENDPOINT}（如 {@code http://127.0.0.1:4318}）未配时默认静默关闭；</li>
 *   <li>span 名与属性只允许低基数（服务/方法、topic、gate 事件名），绝不放玩家 id 等高基数值。</li>
 * </ul>
 */
public final class OtlpHttpSpanExporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtlpHttpSpanExporter.class);

    public static final String ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT";
    public static final String SERVICE_NAME_ENV = "OTEL_SERVICE_NAME";

    public static final int DEFAULT_QUEUE_CAPACITY = 2048;
    public static final int DEFAULT_MAX_BATCH_SIZE = 512;
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 5_000L;
    public static final Duration DEFAULT_EXPORT_TIMEOUT = Duration.ofSeconds(30);

    public enum SpanKind {
        INTERNAL(1),
        SERVER(2),
        CLIENT(3),
        PRODUCER(4),
        CONSUMER(5);

        private final int otlpCode;

        SpanKind(int otlpCode) {
            this.otlpCode = otlpCode;
        }

        public int otlpCode() {
            return otlpCode;
        }
    }

    /** 一条待导出的已完成 span 记录（不含高基数玩家 ID）。 */
    public record SpanRecord(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            SpanKind kind,
            long startTimeUnixNano,
            long endTimeUnixNano,
            boolean error,
            String errorMessage,
            Map<String, String> attributes) {

        public SpanRecord {
            Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(spanId, "spanId");
            parentSpanId = parentSpanId == null ? "" : parentSpanId;
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            errorMessage = errorMessage == null ? "" : errorMessage;
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    @FunctionalInterface
    public interface Transport {
        /** 发送 OTLP/HTTP JSON 批次；返回 true 表示 2xx 接收成功，false 或抛异常均按丢批计数且不重试。 */
        boolean post(URI endpoint, byte[] jsonBody, Duration timeout) throws Exception;
    }

    private static final AtomicReference<OtlpHttpSpanExporter> GLOBAL = new AtomicReference<>();

    private final String serviceName;
    private final URI tracesEndpoint;
    private final int maxBatchSize;
    private final long flushIntervalMs;
    private final Duration exportTimeout;
    private final Transport transport;
    private final ArrayBlockingQueue<SpanRecord> queue;
    private final AtomicLong exportedSpans = new AtomicLong();
    private final AtomicLong droppedSpans = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService flusher;

    public OtlpHttpSpanExporter(String serviceName, URI baseOrTracesEndpoint) {
        this(serviceName, normalizeTracesUri(baseOrTracesEndpoint), DEFAULT_QUEUE_CAPACITY,
                DEFAULT_MAX_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS, DEFAULT_EXPORT_TIMEOUT, defaultTransport());
    }

    public OtlpHttpSpanExporter(String serviceName, URI tracesEndpoint, int queueCapacity, int maxBatchSize,
                                long flushIntervalMs, Duration exportTimeout, Transport transport) {
        this.serviceName = (serviceName == null || serviceName.isBlank()) ? "game-service" : serviceName.strip();
        this.tracesEndpoint = Objects.requireNonNull(tracesEndpoint, "tracesEndpoint");
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.flushIntervalMs = Math.max(100L, flushIntervalMs);
        this.exportTimeout = exportTimeout == null ? DEFAULT_EXPORT_TIMEOUT : exportTimeout;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.flusher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("otlp-span-exporter").factory());
    }

    /** 按环境变量初始化并安装全局导出器（未配置 OTEL_EXPORTER_OTLP_ENDPOINT 时返回 null，保持零开销）。 */
    public static OtlpHttpSpanExporter installFromEnv(String fallbackServiceName) {
        String endpoint = System.getenv(ENDPOINT_ENV);
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String svc = System.getenv(SERVICE_NAME_ENV);
        if (svc == null || svc.isBlank()) {
            svc = fallbackServiceName;
        }
        OtlpHttpSpanExporter exporter = new OtlpHttpSpanExporter(svc, URI.create(endpoint.strip()));
        exporter.start();
        GLOBAL.set(exporter);
        return exporter;
    }

    public static void setGlobal(OtlpHttpSpanExporter exporter) {
        GLOBAL.set(exporter);
    }

    public static OtlpHttpSpanExporter global() {
        return GLOBAL.get();
    }

    /** 向全局导出器提交一条已完成 span（未启用时无操作）。 */
    public static void recordGlobal(SpanRecord span) {
        OtlpHttpSpanExporter exporter = GLOBAL.get();
        if (exporter != null && span != null) {
            exporter.offer(span);
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            flusher.scheduleWithFixedDelay(this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 非阻塞入队；队列满时直接丢弃并计入 droppedSpans。 */
    public boolean offer(SpanRecord span) {
        if (span == null || closed.get()) {
            return false;
        }
        if (!queue.offer(span)) {
            droppedSpans.incrementAndGet();
            return false;
        }
        return true;
    }

    /** 立即排空至多 {@link #maxBatchSize} 条 span 发出；失败丢批计数，不重试。 */
    public int flushOnce() {
        if (queue.isEmpty()) {
            return 0;
        }
        List<SpanRecord> batch = new ArrayList<>(Math.min(queue.size(), maxBatchSize));
        queue.drainTo(batch, maxBatchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        byte[] json = encodeOtlpJson(serviceName, batch);
        try {
            boolean ok = transport.post(tracesEndpoint, json, exportTimeout);
            if (ok) {
                exportedSpans.addAndGet(batch.size());
                return batch.size();
            }
            failedBatches.incrementAndGet();
            droppedSpans.addAndGet(batch.size());
            return 0;
        } catch (Exception e) {
            failedBatches.incrementAndGet();
            droppedSpans.addAndGet(batch.size());
            log.debug("[otlp] export batch dropped size={} endpoint={} err={}",
                    batch.size(), tracesEndpoint, e.getClass().getSimpleName());
            return 0;
        }
    }

    private void flushSafely() {
        try {
            while (!queue.isEmpty() && !Thread.currentThread().isInterrupted()) {
                if (flushOnce() == 0) {
                    break;
                }
            }
        } catch (Throwable t) {
            log.warn("[otlp] background flush caught throwable", t);
        }
    }

    public long exportedSpans() {
        return exportedSpans.get();
    }

    public long droppedSpans() {
        return droppedSpans.get();
    }

    public long failedBatches() {
        return failedBatches.get();
    }

    public int bufferedSpans() {
        return queue.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        GLOBAL.compareAndSet(this, null);
        flusher.shutdownNow();
        flushOnce();
    }

    static URI normalizeTracesUri(URI base) {
        Objects.requireNonNull(base, "endpoint");
        String raw = base.toString();
        if (raw.endsWith("/v1/traces")) {
            return base;
        }
        String trimmed = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        return URI.create(trimmed + "/v1/traces");
    }

    /** 构造标准 OTLP/HTTP JSON {@code ExportTraceServiceRequest} 载荷。 */
    public static byte[] encodeOtlpJson(String serviceName, List<SpanRecord> spans) {
        StringBuilder sb = new StringBuilder(256 + spans.size() * 256);
        sb.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"");
        escapeJson(sb, serviceName);
        sb.append("\"}}]},\"scopeSpans\":[{\"scope\":{\"name\":\"com.game.trace\"},\"spans\":[");
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SpanRecord s = spans.get(i);
            sb.append("{\"traceId\":\"");
            escapeJson(sb, s.traceId());
            sb.append("\",\"spanId\":\"");
            escapeJson(sb, s.spanId());
            sb.append("\"");
            if (!s.parentSpanId().isEmpty()) {
                sb.append(",\"parentSpanId\":\"");
                escapeJson(sb, s.parentSpanId());
                sb.append("\"");
            }
            sb.append(",\"name\":\"");
            escapeJson(sb, s.name());
            sb.append("\",\"kind\":").append(s.kind().otlpCode());
            sb.append(",\"startTimeUnixNano\":\"").append(Long.toUnsignedString(s.startTimeUnixNano())).append("\"");
            sb.append(",\"endTimeUnixNano\":\"").append(Long.toUnsignedString(s.endTimeUnixNano())).append("\"");
            if (!s.attributes().isEmpty()) {
                sb.append(",\"attributes\":[");
                int idx = 0;
                for (Map.Entry<String, String> entry : s.attributes().entrySet()) {
                    if (idx++ > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"key\":\"");
                    escapeJson(sb, entry.getKey());
                    sb.append("\",\"value\":{\"stringValue\":\"");
                    escapeJson(sb, entry.getValue());
                    sb.append("\"}}");
                }
                sb.append(']');
            }
            sb.append(",\"status\":{\"code\":").append(s.error() ? 2 : 1);
            if (s.error() && !s.errorMessage().isEmpty()) {
                sb.append(",\"message\":\"");
                escapeJson(sb, s.errorMessage());
                sb.append("\"");
            }
            sb.append("}}");
        }
        sb.append("]}]}]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void escapeJson(StringBuilder sb, String s) {
        if (s == null) {
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return (endpoint, jsonBody, timeout) -> {
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        };
    }
}
