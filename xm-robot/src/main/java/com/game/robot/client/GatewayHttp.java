package com.game.robot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 调 xm-gateway 的 HTTP 接口（JSON 进出，只接受 HTTP 200）。 */
public final class GatewayHttp {

    private final String gatewayUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GatewayHttp(String gatewayUrl, Duration timeout) {
        this.gatewayUrl = gatewayUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public JsonNode get(String path) throws RobotException {
        return parse(send(HttpRequest.newBuilder(URI.create(gatewayUrl + path)).timeout(timeout).GET().build()));
    }

    public JsonNode post(String path, String body) throws RobotException {
        return parse(postRaw(path, body));
    }

    /** POST 并返回原始应答体（只接受 HTTP 200）。 */
    public String postRaw(String path, String body) throws RobotException {
        return send(HttpRequest.newBuilder(URI.create(gatewayUrl + path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private String send(HttpRequest request) throws RobotException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RobotException(request.uri().getPath() + " 返回 HTTP " + response.statusCode() + "："
                        + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new RobotException("调用 xm-gateway 失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-gateway 被中断", e);
        }
    }

    private JsonNode parse(String body) throws RobotException {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new RobotException("xm-gateway 应答不是 JSON：" + body, e);
        }
    }
}
