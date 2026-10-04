package com.game.robot.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@code POST {gateway}/api/login} 与 {@code /api/refresh-token}（robot 契约 §11 / mmorpg robot/http_login.go）：
 * 只接受 HTTP 200，业务结果在 body 的 {@code code}；JSON 键 snake_case。返回解析后的 JSON，由场景逐项核对。线程安全。
 */
public final class LoginHttpClient {

    static final String LOGIN_PATH = "/api/login";
    static final String REFRESH_PATH = "/api/refresh-token";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    public LoginHttpClient(String gatewayBaseUrl, Duration timeout) {
        this.baseUrl = gatewayBaseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** 口令登录（auth_type 留空，同 Go robot 的 use_http_login 口令路径）。 */
    public JsonNode loginWithPassword(int zoneId, String account, String password) throws RobotException {
        ObjectNode body = JSON.createObjectNode();
        body.put("zone_id", zoneId);
        body.put("account", account);
        body.put("password", password);
        return post(LOGIN_PATH, body);
    }

    public JsonNode refresh(String refreshToken) throws RobotException {
        ObjectNode body = JSON.createObjectNode();
        if (refreshToken != null) {
            body.put("refresh_token", refreshToken);
        }
        return post(REFRESH_PATH, body);
    }

    private JsonNode post(String path, ObjectNode body) throws RobotException {
        URI endpoint = URI.create(baseUrl + path);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RobotException(path + " 请求失败（" + endpoint + "）：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException(path + " 被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new RobotException(path + " 应答 HTTP " + response.statusCode() + "（契约恒为 200，业务结果放 body.code）");
        }
        try {
            JsonNode root = JSON.readTree(response.body());
            if (root == null || !root.isObject() || root.get("code") == null || !root.get("code").isIntegralNumber()) {
                throw new RobotException(path + " 应答不是带整数 code 的 JSON 对象");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new RobotException(path + " 应答不是合法 JSON", e);
        }
    }
}
