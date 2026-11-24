package com.game.login.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.compliance.AntiAddictionRules;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 国家新闻出版署「网络游戏防沉迷实名认证系统」客户端（《接口对接技术规范》V1.9，对齐 Go {@code go/login/internal/logic/pkg/nppa/client.go}）。
 *
 * <p>纯 JDK 21 实现：AES-128-GCM 加密请求体（{@code Base64(nonce12 || ciphertext || tag16)}）、SHA-256 请求头签名。
 * 错误消息绝不回显姓名、身份证号或密钥明文。
 */
public final class NppaClient implements AutoCloseable {

    public static final String DEFAULT_CHECK_URL = "https://api.wlc.nppa.gov.cn/idcard/authentication/check";
    public static final String DEFAULT_QUERY_URL = "http://api2.wlc.nppa.gov.cn/idcard/authentication/query";
    public static final String DEFAULT_LOGINOUT_URL = "http://api2.wlc.nppa.gov.cn/behavior/collection/loginout";

    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    public static final int MAX_BATCH = 128;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum AuthStatus {
        OK(0), PENDING(1), FAILED(2);
        public final int code;
        AuthStatus(int code) { this.code = code; }
    }

    public record AuthResult(AuthStatus status, String pi) {
    }

    public record Behavior(int no, String si, int bt, long ot, int ct, String pi) {
        public static final int LOGOUT = 0;
        public static final int LOGIN = 1;
    }

    public record Config(String appId, String bizId, String secretKeyHex,
                         String checkUrl, String queryUrl, String loginoutUrl) {
    }

    /** 实名核验最小接口（供 {@link RealNameService} 依赖与单测替身替换）。 */
    public interface Verifier {
        AuthResult check(String ai, String name, String idNumber) throws NppaException;
        AuthResult query(String ai) throws NppaException;
    }

    public enum ErrorKind {
        INVALID_IDENTITY,
        NO_RECORD,
        UNAVAILABLE
    }

    public static final class NppaException extends Exception {
        private final ErrorKind kind;

        public NppaException(ErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public NppaException(ErrorKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public ErrorKind kind() {
            return kind;
        }
    }

    private final String appId;
    private final String bizId;
    private final String secretKeyHex;
    private final SecretKeySpec aesKey;
    private final String checkUrl;
    private final String queryUrl;
    private final String loginoutUrl;
    private final HttpClient http;
    private final boolean ownsHttp;
    private final Clock clock;

    public NppaClient(Config cfg) {
        this(cfg, null, Clock.systemUTC());
    }

    public NppaClient(Config cfg, HttpClient http, Clock clock) {
        Objects.requireNonNull(cfg, "cfg");
        if (isBlank(cfg.appId()) || isBlank(cfg.bizId())) {
            throw new IllegalArgumentException("nppa: appId and bizId are required");
        }
        if (isBlank(cfg.secretKeyHex()) || cfg.secretKeyHex().length() != 32) {
            throw new IllegalArgumentException("nppa: secretKey must be 32 hex characters");
        }
        byte[] keyBytes;
        try {
            keyBytes = HexFormat.of().parseHex(cfg.secretKeyHex());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("nppa: secretKey must be valid hex", e);
        }
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("nppa: secretKey must decode to 16 bytes");
        }
        this.appId = cfg.appId();
        this.bizId = cfg.bizId();
        this.secretKeyHex = cfg.secretKeyHex();
        this.aesKey = new SecretKeySpec(keyBytes, "AES");
        this.checkUrl = isBlank(cfg.checkUrl()) ? DEFAULT_CHECK_URL : cfg.checkUrl();
        this.queryUrl = isBlank(cfg.queryUrl()) ? DEFAULT_QUERY_URL : cfg.queryUrl();
        this.loginoutUrl = isBlank(cfg.loginoutUrl()) ? DEFAULT_LOGINOUT_URL : cfg.loginoutUrl();
        this.ownsHttp = http == null;
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build() : http;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public void close() {
        if (ownsHttp) {
            http.close();
        }
    }

    /** 适配 {@link Verifier}。 */
    public Verifier asVerifier() {
        return new Verifier() {
            @Override
            public AuthResult check(String ai, String name, String idNumber) throws NppaException {
                return NppaClient.this.check(ai, name, idNumber);
            }

            @Override
            public AuthResult query(String ai) throws NppaException {
                return NppaClient.this.query(ai);
            }
        };
    }

    public AuthResult check(String ai, String name, String idNumber) throws NppaException {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("ai", ai);
        plain.put("name", name);
        plain.put("idNum", idNumber);
        JsonNode resp = post(checkUrl, plain);
        return parseAuthResult(resp);
    }

    public AuthResult query(String ai) throws NppaException {
        JsonNode resp = get(queryUrl, Map.of("ai", ai));
        return parseAuthResult(resp);
    }

    /**
     * 上报一批（1..128 条）上下线事件。返回被系统按 3001 部分拒收的条目序号列表（空列表表示全批接收）；
     * 整批失败抛出 {@link NppaException} 供调用方重试。
     */
    public List<Integer> loginout(List<Behavior> batch) throws NppaException {
        if (batch == null || batch.isEmpty() || batch.size() > MAX_BATCH) {
            throw new IllegalArgumentException("nppa: batch size out of range 1.." + MAX_BATCH);
        }
        List<Map<String, Object>> collections = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Behavior b = batch.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("no", i + 1);
            item.put("si", b.si());
            item.put("bt", b.bt());
            item.put("ot", b.ot());
            item.put("ct", b.ct());
            item.put("pi", b.pi());
            collections.add(item);
        }
        JsonNode resp = post(loginoutUrl, Map.of("collections", collections));
        int errCode = resp.path("errcode").asInt(-1);
        if (errCode == 0) {
            return List.of();
        }
        if (errCode == 3001) {
            JsonNode results = resp.path("data").path("results");
            if (!results.isArray()) {
                throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: partial failure without results array");
            }
            List<Integer> rejected = new ArrayList<>();
            for (JsonNode r : results) {
                if (r.path("errcode").asInt(0) != 0) {
                    rejected.add(r.path("no").asInt());
                }
            }
            return rejected;
        }
        throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: errcode=" + errCode);
    }

    private AuthResult parseAuthResult(JsonNode resp) throws NppaException {
        int errCode = resp.path("errcode").asInt(-1);
        if (errCode == 2001 || errCode == 2005) {
            throw new NppaException(ErrorKind.INVALID_IDENTITY, "nppa: invalid identity errcode=" + errCode);
        }
        if (errCode == 2003) {
            throw new NppaException(ErrorKind.NO_RECORD, "nppa: no authentication record for ai");
        }
        if (errCode != 0) {
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: errcode=" + errCode);
        }
        JsonNode result = resp.path("data").path("result");
        if (!result.isObject() || !result.has("status")) {
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: unparsable result");
        }
        int status = result.path("status").asInt(-1);
        return switch (status) {
            case 0 -> {
                String pi = result.path("pi").asText("");
                if (!AntiAddictionRules.validPi(pi)) {
                    throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: malformed pi in successful result");
                }
                yield new AuthResult(AuthStatus.OK, pi);
            }
            case 1 -> new AuthResult(AuthStatus.PENDING, "");
            case 2 -> new AuthResult(AuthStatus.FAILED, "");
            default -> throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: unknown auth status " + status);
        };
    }

    private JsonNode post(String targetUrl, Object plainObj) throws NppaException {
        try {
            byte[] plain = JSON.writeValueAsBytes(plainObj);
            String encrypted = encrypt(plain);
            String body = JSON.writeValueAsString(Map.of("data", encrypted));
            String ts = Long.toString(clock.millis());
            Map<String, String> params = Map.of("appId", appId, "bizId", bizId, "timestamps", ts);
            HttpRequest req = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json;charset=utf-8")
                    .header("appId", appId)
                    .header("bizId", bizId)
                    .header("timestamps", ts)
                    .header("sign", sign(secretKeyHex, params, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return execute(req);
        } catch (NppaException e) {
            throw e;
        } catch (Exception e) {
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: post failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private JsonNode get(String targetUrl, Map<String, String> query) throws NppaException {
        try {
            StringBuilder url = new StringBuilder(targetUrl).append('?');
            boolean first = true;
            for (var entry : query.entrySet()) {
                if (!first) url.append('&');
                first = false;
                url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            String ts = Long.toString(clock.millis());
            Map<String, String> params = new TreeMap<>();
            params.put("appId", appId);
            params.put("bizId", bizId);
            params.put("timestamps", ts);
            params.putAll(query);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("appId", appId)
                    .header("bizId", bizId)
                    .header("timestamps", ts)
                    .header("sign", sign(secretKeyHex, params, ""))
                    .GET()
                    .build();
            return execute(req);
        } catch (NppaException e) {
            throw e;
        } catch (Exception e) {
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: get failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private JsonNode execute(HttpRequest req) throws NppaException {
        CompletableFuture<HttpResponse<InputStream>> pending = null;
        try {
            pending = http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> resp = pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try (InputStream in = resp.body()) {
                if (resp.statusCode() < 200 || resp.statusCode() > 299) {
                    throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: http status " + resp.statusCode());
                }
                byte[] raw = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (raw.length > MAX_RESPONSE_BYTES) {
                    throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: unreadable or oversized response");
                }
                return JSON.readTree(raw);
            }
        } catch (NppaException e) {
            throw e;
        } catch (InterruptedException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: interrupted", e);
        } catch (TimeoutException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: timeout", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: io error " + cause.getClass().getSimpleName(), cause);
        } catch (IOException e) {
            throw new NppaException(ErrorKind.UNAVAILABLE, "nppa: io error " + e.getClass().getSimpleName(), e);
        }
    }

    /** AES-128-GCM：返回 {@code Base64(nonce12 || ciphertext || tag16)}。 */
    String encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ct = cipher.doFinal(plain);
        byte[] combined = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, combined, 0, nonce.length);
        System.arraycopy(ct, 0, combined, nonce.length, ct.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    /** 规范第五节签名：{@code SHA-256(secretKey + sorted(k+v) + body)} 的小写十六进制。 */
    public static String sign(String secretKeyHex, Map<String, String> params, String body) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        sb.append(secretKeyHex);
        for (var entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append(entry.getValue());
        }
        if (body != null) {
            sb.append(body);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** 生成 32 位小写十六进制标识（用于单次认证 {@code ai} 与单次会话 {@code si}）。 */
    public static String newId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
