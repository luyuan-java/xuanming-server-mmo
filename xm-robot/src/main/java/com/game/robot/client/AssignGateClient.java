package com.game.robot.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * {@code POST {gateway}/api/assign-gate}（robot 契约 §2.1）。
 *
 * <ul>
 *   <li>请求体正好是 {@code {"zone_id":N}}（Go robot 的 account / device_id / queue_token 都是 omitempty 空串）；</li>
 *   <li>只接受 HTTP 200，业务结果在 body 的 {@code code}；JSON 键 snake_case；</li>
 *   <li>{@code token_payload} / {@code token_signature} 是<b>标准 Base64</b>（带填充）字符串。</li>
 * </ul>
 * 开服限流（100 + {@code queue_source="ratelimit"}、429 {@code IP_RATE_LIMIT}）按 {@code retry_after_ms} 退避重发（≤0 按 2 s，同 Go robot；
 * 429 按 1 s），累计至多 {@link #RATE_LIMIT_BUDGET}——本机全部机器人共用 127.0.0.1 一个 IP 桶，多账号同时进场会撞上。
 * 其余不重试：不做 Go robot 的 30 次退避重试与登录排队（100 login / 410）轮询，探针要的是「这一次准入是否符合契约」，失败即报告。
 * 线程安全（{@link HttpClient} 与 {@link ObjectMapper} 都可共享）。
 */
public final class AssignGateClient {

    static final String PATH = "/api/assign-gate";
    /** 开服限流退避的累计上限（smoke 满 200 个账号时等 IP 桶补完约 36 s）。 */
    static final Duration RATE_LIMIT_BUDGET = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;

    /**
     * @param gatewayBaseUrl 例如 {@code http://127.0.0.1:18081}，不带结尾 {@code /}
     * @param timeout        建连与单次请求的超时（Go robot 单次 5s）
     */
    public AssignGateClient(String gatewayBaseUrl, Duration timeout) {
        this.endpoint = URI.create(gatewayBaseUrl + PATH);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
    }

    public GateAssignment assign(int zoneId) throws RobotException {
        long deadline = System.nanoTime() + RATE_LIMIT_BUDGET.toNanos();
        while (true) {
            HttpResponse<String> response = send(zoneId);
            long backoff = response.statusCode() == 200 ? rateLimitBackoffMs(response.body()) : -1;
            if (backoff < 0 || System.nanoTime() + backoff * 1_000_000L > deadline) {
                return parse(response.statusCode(), response.body());
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RobotException("assign-gate 被中断", e);
            }
        }
    }

    private HttpResponse<String> send(int zoneId) throws RobotException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(zoneId), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RobotException("assign-gate 请求失败（" + endpoint + "）：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("assign-gate 被中断", e);
        }
        return response;
    }

    /** 开服限流时该等多少毫秒再发；不是限流（或应答不是 JSON）为 -1。纯函数，便于单测。 */
    static long rateLimitBackoffMs(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            return -1;
        }
        if (root == null || !root.isObject()) {
            return -1;
        }
        int code = root.path("code").asInt(-1);
        if (code == 100 && "ratelimit".equals(root.path("queue_source").asText())) {
            long retry = root.path("retry_after_ms").asLong(0);
            return retry > 0 ? retry : 2000;
        }
        if (code == 429 && "IP_RATE_LIMIT".equals(root.path("error").asText())) {
            return 1000;
        }
        return -1;
    }

    /** 请求体：与 Go robot 首次请求逐字节相同。 */
    static String requestBody(int zoneId) {
        return "{\"zone_id\":" + zoneId + "}";
    }

    /**
     * 解析应答。纯函数，便于单测。
     *
     * @throws RobotException 非 200、不是 JSON 对象、{@code code≠0}、准入但缺 gate 地址 / 端口、Base64 非法
     */
    public static GateAssignment parse(int httpStatus, String body) throws RobotException {
        if (httpStatus != 200) {
            throw new RobotException("assign-gate 应答 HTTP " + httpStatus + "（契约恒为 200，业务结果放 body.code）："
                    + abbreviate(body));
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new RobotException("assign-gate 应答不是合法 JSON：" + abbreviate(body), e);
        }
        if (root == null || !root.isObject()) {
            throw new RobotException("assign-gate 应答不是 JSON 对象：" + abbreviate(body));
        }
        JsonNode code = root.get("code");
        if (code == null || !code.isIntegralNumber()) {
            throw new RobotException("assign-gate 应答缺整数 code：" + abbreviate(body));
        }
        if (code.asInt() != 0) {
            throw new RobotException("assign-gate 未准入 code=" + code.asInt() + " error=" + text(root, "error"));
        }
        String gateIp = text(root, "gate_ip");
        if (gateIp.isEmpty()) {
            throw new RobotException("assign-gate 准入但 gate_ip 为空");
        }
        JsonNode port = root.get("gate_port");
        // 契约：gate_port 是数字；"10000" 这样的字符串会让 Go robot 解析失败。
        if (port == null || !port.isIntegralNumber() || port.asInt() <= 0 || port.asInt() > 65535) {
            throw new RobotException("assign-gate 准入但 gate_port 不是 1–65535 的数字：" + port);
        }
        byte[] payload = base64(root, "token_payload");
        byte[] signature = base64(root, "token_signature");
        JsonNode deadline = root.get("token_deadline");
        long tokenDeadline = deadline != null && deadline.isIntegralNumber() ? deadline.asLong() : 0;
        return new GateAssignment(gateIp, port.asInt(), payload, signature, tokenDeadline);
    }

    private static byte[] base64(JsonNode root, String field) throws RobotException {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return new byte[0];
        }
        if (!node.isTextual()) {
            throw new RobotException("assign-gate 的 " + field + " 应是标准 Base64 字符串，实际：" + node.getNodeType());
        }
        try {
            return Base64.getDecoder().decode(node.asText());
        } catch (IllegalArgumentException e) {
            throw new RobotException("assign-gate 的 " + field + " 不是标准 Base64：" + e.getMessage(), e);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "<空>";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
