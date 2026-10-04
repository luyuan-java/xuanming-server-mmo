package com.game.login.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 三方 OAuth 校验共用的 HTTP GET（JDK HttpClient，整个交换 5 s 总时限同基线、响应体上限 64 KiB）。线程安全。 */
final class OAuthHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final Duration timeout;
    private final HttpClient client;

    OAuthHttp() {
        this(TIMEOUT);
    }

    /** @param timeout 整个交换的总时限（测试用短的） */
    OAuthHttp(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * GET base + path?query，返回响应体（不看状态码，同基线：业务错误在 JSON 里）。
     * 抛出的异常一律只带异常类名：URI 的查询串里有 code / access_token / secret，JDK 的 URI / 请求构造异常会把整条 URI 写进消息。
     */
    String get(String base, String path, Map<String, String> query) {
        StringJoiner joined = new StringJoiner("&");
        query.forEach((k, v) -> joined.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            URI uri = URI.create(trimSlash(base) + path + "?" + joined);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            // 整个交换（含读响应体）一个总时限，同 Go http.Client.Timeout：JDK 的请求超时只管到响应头，
            // 响应体读到一半卡住会把 login 工作线程一直挂着
            pending = client.sendAsync(request, responseInfo -> new LimitedBody());
            byte[] body = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body();
            return new String(body, StandardCharsets.UTF_8);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("三方认证 HTTP 请求失败: " + cause.getClass().getSimpleName());
        } catch (TimeoutException | RuntimeException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            throw new IllegalStateException("三方认证 HTTP 请求失败: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("三方认证 HTTP 请求被中断");
        }
    }

    /** 响应体最多 {@link #MAX_BODY_BYTES}，超出取消并按失败（三方应答是几百字节的 JSON）。非阻塞，跑在 HttpClient 自己的线程上。 */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : items) {
                if (out.size() + buffer.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("三方认证应答超过 " + MAX_BODY_BYTES + " 字节"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    /**
     * 启动时校验三方接口地址：http / https、有主机名（docker 服务名带下划线时 JDK 解析不出主机，请求必然失败）。
     * 报错不回显地址本身（可能带凭据）。
     */
    static String requireEndpoint(String endpoint, String what) {
        URI uri;
        try {
            uri = URI.create(trimSlash(endpoint));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " 地址不是合法 URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(what + " 地址须是 http(s)://主机[:端口][/路径]（主机名不能含下划线，不带查询串与用户信息）");
        }
        return endpoint;
    }

    static String trimSlash(String base) {
        String b = base;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
