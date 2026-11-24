package com.game.common.startup;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 启动时核对本机时钟：与 Kubernetes API server 的时钟差 30 秒以上即拒绝启动。
 *
 * <p>对齐权威仓 {@code java/server_startup/ClockCheck.java}、Go {@code clockcheck} 与 C++ {@code clock_check.h}：
 * 开关同为 {@code MMORPG_CLOCK_CHECK_MODE}（仅精确值 {@code "warn"} 超限放行，其余取值均拒启）。
 * 参照为 API server 响应里的 {@code Date} 头（{@code GET /version} 无需鉴权）；非 Kubernetes 环境或无法访问时跳过不阻塞。
 */
public final class ClockCheck {

    /** 允许的最大偏差：Date 头精确到秒，加往返误差在 2 秒以内，30 秒拦截明显偏钟。 */
    public static final long MAX_OFFSET_MILLIS = 30_000;

    /** 设为 {@code warn} 时超限仅告警并照常启动；其余取值均按拒启处理。 */
    public static final String MODE_ENV = "MMORPG_CLOCK_CHECK_MODE";

    private static final String CLUSTER_CA = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ClockCheck() {
    }

    /** 偏差超限时向 stderr 写明原因并以退出码 1 结束进程；在 SpringApplication.run 之前调用。 */
    public static void mustPass(String service) {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || host.isEmpty() || port == null || port.isEmpty()) {
            return;
        }
        long offset;
        try {
            offset = measure(host, port);
        } catch (Exception e) {
            System.err.println("clock check skipped: cannot read the Kubernetes API server clock: " + e);
            return;
        }
        String refusal = judge(offset, System.getenv(MODE_ENV));
        if (refusal != null) {
            System.err.println(service + " refuses to start: " + refusal);
            System.exit(1);
        }
    }

    private static long measure(String host, String port) throws Exception {
        String authority = host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + authority + "/version"))
                .timeout(TIMEOUT).GET().build();
        try (HttpClient client = HttpClient.newBuilder().sslContext(clusterContext()).connectTimeout(TIMEOUT).build()) {
            long sent = System.currentTimeMillis();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long received = System.currentTimeMillis();
            String date = response.headers().firstValue("date")
                    .orElseThrow(() -> new IllegalStateException("no Date header"));
            long dateSeconds = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
            return offsetMillis(sent, received, dateSeconds);
        }
    }

    /** 本机时钟减参照时钟（毫秒）：本机取请求往返中点，参照取 Date 所在秒的中点。 */
    public static long offsetMillis(long sentMillis, long receivedMillis, long dateSeconds) {
        return sentMillis + (receivedMillis - sentMillis) / 2 - (dateSeconds * 1000 + 500);
    }

    /** 可启动返回 null；需拒启返回原因说明。仅 {@code "warn"} 在超限时放行。 */
    public static String judge(long offsetMillis, String mode) {
        String measured = "the local clock is " + offsetMillis + " ms off the Kubernetes API server clock";
        if (offsetMillis >= -MAX_OFFSET_MILLIS && offsetMillis <= MAX_OFFSET_MILLIS) {
            System.err.println("clock check passed: " + measured);
            return null;
        }
        String reason = measured + ", more than the " + MAX_OFFSET_MILLIS + " ms allowed; fix the node clock "
                + "(docs/ops/clock-sync-runbook.md), or set " + MODE_ENV + "=warn to start anyway";
        if ("warn".equals(mode)) {
            System.err.println("clock check failed but " + MODE_ENV + "=warn: " + reason);
            return null;
        }
        return reason;
    }

    private static SSLContext clusterContext() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(Path.of(CLUSTER_CA))) {
            int n = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("cluster-ca-" + n++, cert);
            }
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
