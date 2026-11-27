package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 官方通行证（Casdoor）管理接口客户端（对齐 mmorpg {@code passport_admin.go}）：
 * <ul>
 *   <li>账号注销删通行证用户：{@code GET /api/get-user?userId=<sub>} → {@code POST /api/delete-user {owner,name}} → 复查消失；</li>
 *   <li>会话状态核对：{@code GET /api/get-user?userId=<sub>} 或 {@code ?id=<owner>/<name>}，判定被删、被封或改密时刻（RFC 3339）。</li>
 * </ul>
 * 凭据走 HTTP Basic（机密应用 {@code AdminClientId / AdminClientSecret}）；失败一律抛 {@link AuthProviderUnavailableException} 且绝不带凭据。
 */
public final class PassportAdminClient implements AutoCloseable {

    public static final String VERDICT_USER_GONE = "user_gone";
    public static final String VERDICT_USER_FORBIDDEN = "user_forbidden";
    public static final String VERDICT_PASSWORD_CHANGED = "password_changed";

    static final String GET_USER_PATH = "/api/get-user";
    static final String DELETE_USER_PATH = "/api/delete-user";
    static final String USER_REMOVED_MARKER = "Affected";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 通行证中一名用户此刻的状态快照。 */
    public record UserStatus(String sub, boolean gone, boolean forbidden, long passwordChangedAtSeconds) {

        /**
         * 判断身份令牌签发于 {@code issuedAtSeconds}（通行证时钟）的会话是否仍有效：
         * 空串 = 有效；否则返回 {@link #VERDICT_USER_GONE} / {@link #VERDICT_USER_FORBIDDEN} / {@link #VERDICT_PASSWORD_CHANGED}。
         * 同一秒按改密在后处理；{@code issuedAtSeconds <= 0} 只要改过密即判失效。
         */
        public String sessionVerdict(long issuedAtSeconds) {
            if (gone) {
                return VERDICT_USER_GONE;
            }
            if (forbidden) {
                return VERDICT_USER_FORBIDDEN;
            }
            if (passwordChangedAtSeconds > 0L && passwordChangedAtSeconds >= issuedAtSeconds) {
                return VERDICT_PASSWORD_CHANGED;
            }
            return "";
        }
    }

    private record RawUser(String owner, String name, String id, boolean forbidden, boolean deleted, String lastChangePasswordTime) {
    }

    private final String baseUrl;
    private final String basicAuthHeader;
    private final Duration timeout;
    private final HttpClient http;

    public PassportAdminClient(String issuer, String endpoint, String adminClientId, String adminClientSecret,
                               boolean allowHttpEndpoint) {
        this(issuer, endpoint, adminClientId, adminClientSecret, allowHttpEndpoint, OAuthHttp.TIMEOUT);
    }

    PassportAdminClient(String issuer, String endpoint, String adminClientId, String adminClientSecret,
                        boolean allowHttpEndpoint, Duration timeout) {
        if (issuer == null || issuer.isBlank() || adminClientId == null || adminClientId.isBlank()
                || adminClientSecret == null || adminClientSecret.isBlank()) {
            throw new IllegalArgumentException("官方通行证管理接口需要 issuer、admin-client-id 与管理密钥");
        }
        if (!adminClientSecret.equals(adminClientSecret.strip())) {
            throw new IllegalArgumentException("官方通行证管理密钥含首尾空白");
        }
        String resolved = endpoint == null || endpoint.isBlank() ? issuer : endpoint;
        this.baseUrl = OAuthHttp.trimSlash(OAuthHttp.requireEndpoint(resolved, "官方通行证管理接口", allowHttpEndpoint));
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                (adminClientId + ":" + adminClientSecret).getBytes(StandardCharsets.UTF_8));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 按不可变用户 id（令牌 sub）查状态；查不到返回 gone=true。 */
    public UserStatus lookupUser(String sub) {
        RawUser user = findUser(sub);
        if (user == null) {
            return new UserStatus(sub, true, false, 0L);
        }
        return toStatus(user);
    }

    /** 按 {@code owner/name} 查状态（webhook 回调仅给用户名时用）；查不到返回 gone=true 且 sub=""。 */
    public UserStatus lookupUserByName(String owner, String name) {
        RawUser user = getUser(Map.of("id", owner + "/" + name));
        if (user == null) {
            return new UserStatus("", true, false, 0L);
        }
        if (!owner.equals(user.owner()) || !name.equals(user.name()) || user.id().isEmpty()) {
            throw new AuthProviderUnavailableException("passport: get-user returned an unexpected user");
        }
        return toStatus(user);
    }

    /**
     * 删掉 {@code sub} 对应的通行证用户（三步：按 id 查 → 按 owner+name 删并校验 Affected → 再查确认彻底消失）。
     * 已不在直接幂等返回；其余任何异常/Unaffected/软删残留抛 {@link AuthProviderUnavailableException} 由执行器下轮重试。
     */
    public void deleteUser(String sub) {
        RawUser user = findUser(sub);
        if (user == null) {
            return;
        }
        byte[] payload;
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("owner", user.owner());
            body.put("name", user.name());
            payload = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new AuthProviderUnavailableException("passport: encode delete-user failed");
        }
        JsonNode data = call("POST", DELETE_USER_PATH, Map.of(), payload);
        if (data == null || !data.isTextual() || !USER_REMOVED_MARKER.equals(data.asText())) {
            throw new AuthProviderUnavailableException("passport: delete-user did not return Affected");
        }
        RawUser remaining = findUser(sub);
        if (remaining != null) {
            throw new AuthProviderUnavailableException("passport: user still present after delete-user");
        }
    }

    private RawUser findUser(String sub) {
        RawUser user = getUser(Map.of("userId", sub));
        if (user == null) {
            return null;
        }
        if (!sub.equals(user.id()) || user.owner().isEmpty() || user.name().isEmpty()) {
            throw new AuthProviderUnavailableException("passport: get-user returned an unexpected user");
        }
        return user;
    }

    private RawUser getUser(Map<String, String> query) {
        JsonNode data = call("GET", GET_USER_PATH, query, null);
        if (data == null || data.isNull() || data.isMissingNode()) {
            return null;
        }
        if (!data.isObject()) {
            throw new AuthProviderUnavailableException("passport: get-user data is not an object");
        }
        return new RawUser(
                data.path("owner").asText(""),
                data.path("name").asText(""),
                data.path("id").asText(""),
                data.path("isForbidden").asBoolean(false),
                data.path("isDeleted").asBoolean(false),
                data.path("lastChangePasswordTime").asText(""));
    }

    private static UserStatus toStatus(RawUser user) {
        long changedAt = 0L;
        if (!user.lastChangePasswordTime().isEmpty()) {
            try {
                Instant parsed = OffsetDateTime.parse(user.lastChangePasswordTime()).toInstant();
                changedAt = parsed.getEpochSecond();
            } catch (Exception e) {
                throw new AuthProviderUnavailableException("passport: lastChangePasswordTime is not RFC 3339");
            }
        }
        return new UserStatus(user.id(), user.deleted(), user.forbidden(), changedAt);
    }

    private JsonNode call(String method, String path, Map<String, String> query, byte[] body) {
        String target = baseUrl + path;
        if (query != null && !query.isEmpty()) {
            StringJoiner sj = new StringJoiner("&");
            query.forEach((k, v) -> sj.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(v, StandardCharsets.UTF_8)));
            target = target + "?" + sj;
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        HttpResponse<byte[]> resp;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(timeout)
                    .header("Authorization", basicAuthHeader);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            } else {
                builder.GET();
            }
            pending = http.sendAsync(builder.build(), info -> info.statusCode() >= 200 && info.statusCode() < 300
                    ? new OAuthHttp.LimitedBody()
                    : HttpResponse.BodySubscribers.replacing(new byte[0]));
            resp = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AuthProviderUnavailableException("passport: admin http error: " + cause.getClass().getSimpleName());
        } catch (TimeoutException | RuntimeException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            throw new AuthProviderUnavailableException("passport: admin http error: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new AuthProviderUnavailableException("passport: admin http interrupted");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new AuthProviderUnavailableException("passport: admin api http status " + resp.statusCode());
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body());
        } catch (Exception e) {
            throw new AuthProviderUnavailableException("passport: bad admin api json");
        }
        String status = root.path("status").asText("");
        if (!"ok".equals(status)) {
            String msg = root.path("msg").asText("");
            if (msg.length() > 120) {
                msg = msg.substring(0, 120);
            }
            throw new AuthProviderUnavailableException("passport: admin api refused (" + msg + ")");
        }
        return root.get("data");
    }

    @Override
    public void close() {
        http.close();
    }
}
