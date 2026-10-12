package com.game.robot.client;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假 game-gateway（批次 5.4 的测试替身）：只有探针用到的两个接口——{@code POST /api/assign-gate}（按请求的 {@code zone_id} 给那个区的
 * gate 与令牌）与 {@code GET /api/server-list}（各区的显示状态）。形状同真 game-gateway；配 {@link FakeGate} 用。
 */
public final class FakeGateway implements AutoCloseable {

    private record Assignment(GateEndpoint gate, ByteString payload, ByteString signature, long deadline) {
    }

    private final HttpServer http;
    /** 每个区的 gate（不止一台时 assign-gate 按调用次数轮流给）。 */
    private final Map<Integer, List<Assignment>> assignments = new HashMap<>();
    private final Map<Integer, String> statuses = new TreeMap<>();
    private final Map<Integer, Integer> assignCalls = new HashMap<>();
    private int serverListCalls;

    public FakeGateway() throws IOException {
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", this::handle);
        http.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    /** 之后这个区的 assign-gate 给这台 gate 与这份令牌（令牌非空，探针就会握手）；同时把这个区记成 OPEN。 */
    public synchronized FakeGateway assign(int zone, GateEndpoint gate, ByteString tokenPayload, ByteString tokenSignature) {
        List<Assignment> gates = new ArrayList<>();
        gates.add(new Assignment(gate, tokenPayload, tokenSignature, System.currentTimeMillis() / 1000 + 300));
        assignments.put(zone, gates);
        statuses.putIfAbsent(zone, "OPEN");
        return this;
    }

    /**
     * 给这个区再加一台 gate（区里不止一台时）：之后这个区的 assign-gate 按调用次数在各台之间轮流给——第 n 次（从 0 起）给第 n % 台数 台。
     * 先要 {@link #assign} 过这个区。
     */
    public synchronized FakeGateway addGate(int zone, GateEndpoint gate, ByteString tokenPayload, ByteString tokenSignature) {
        List<Assignment> gates = assignments.get(zone);
        if (gates == null) {
            throw new IllegalStateException("区 " + zone + " 还没有 assign 过");
        }
        gates.add(new Assignment(gate, tokenPayload, tokenSignature, System.currentTimeMillis() / 1000 + 300));
        return this;
    }

    /** 同上，令牌给可读的文本（只要握手发生、不关心令牌内容的用例用）。 */
    public FakeGateway assign(int zone, GateEndpoint gate, String tokenPayload, String tokenSignature) {
        return assign(zone, gate, ByteString.copyFromUtf8(tokenPayload), ByteString.copyFromUtf8(tokenSignature));
    }

    /** 区服列表里某个区的显示状态（OPEN / MAINTENANCE / …）。 */
    public synchronized FakeGateway status(int zone, String status) {
        statuses.put(zone, status);
        return this;
    }

    /** 某个区的 assign-gate 至今被调了几次。 */
    public synchronized int assignCalls(int zone) {
        return assignCalls.getOrDefault(zone, 0);
    }

    /** 区服列表至今被读了几次。 */
    public synchronized int serverListCalls() {
        return serverListCalls;
    }

    @Override
    public void close() {
        http.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        int status = 200;
        if (path.equals("/api/assign-gate") && exchange.getRequestMethod().equals("POST")) {
            response = assignGate(body);
        } else if (path.equals("/api/server-list") && exchange.getRequestMethod().equals("GET")) {
            response = serverList();
        } else {
            response = "";
            status = 404;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private synchronized String assignGate(String requestBody) {
        Matcher zone = Pattern.compile("\"zone_id\"\\s*:\\s*(\\d+)").matcher(requestBody);
        int zoneId = zone.find() ? Integer.parseInt(zone.group(1)) : 0;
        int calls = assignCalls.getOrDefault(zoneId, 0);
        assignCalls.merge(zoneId, 1, Integer::sum);
        List<Assignment> gates = assignments.get(zoneId);
        if (gates == null) {
            return "{\"code\":404,\"error\":\"zone_not_found\"}";
        }
        Assignment assignment = gates.get(calls % gates.size());
        Base64.Encoder base64 = Base64.getEncoder();
        return "{\"code\":0,\"gate_ip\":\"" + assignment.gate().host() + "\",\"gate_port\":" + assignment.gate().port()
                + ",\"token_payload\":\"" + base64.encodeToString(assignment.payload().toByteArray())
                + "\",\"token_signature\":\"" + base64.encodeToString(assignment.signature().toByteArray())
                + "\",\"token_deadline\":" + assignment.deadline() + "}";
    }

    private synchronized String serverList() {
        serverListCalls++;
        StringBuilder out = new StringBuilder("{\"zones\":[");
        boolean first = true;
        for (Map.Entry<Integer, String> zone : statuses.entrySet()) {
            out.append(first ? "" : ",").append("{\"zone_id\":").append(zone.getKey()).append(",\"name\":\"").append(zone.getKey())
                    .append(" 区\",\"status\":\"").append(zone.getValue()).append("\"}");
            first = false;
        }
        return out.append("]}").toString();
    }
}
