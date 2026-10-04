package com.game.robot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * xm-data 运维接口（{@code /admin/**}）与服务管理端口指标（{@code /actuator/prometheus}）的 HTTP 客户端。
 * 运维调用带令牌与操作人 {@value #OPERATOR}。
 */
public final class AdminClient {

    public static final String OPERATOR = "xm-robot";

    private final String dataUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public AdminClient(String dataUrl, String token, Duration timeout) {
        this.dataUrl = dataUrl;
        this.token = token;
        this.timeout = timeout;
    }

    /** 运维令牌：环境变量优先，否则本机切片脚本生成的 {@code run/xm-admin-token}；都没有为 null。 */
    public static String resolveToken(String fromEnv) {
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        try {
            Path file = Path.of("run", "xm-admin-token");
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    public static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public JsonNode get(String pathAndQuery) throws RobotException {
        return send("GET", pathAndQuery);
    }

    public JsonNode put(String pathAndQuery) throws RobotException {
        return send("PUT", pathAndQuery);
    }

    public JsonNode delete(String pathAndQuery) throws RobotException {
        return send("DELETE", pathAndQuery);
    }

    /** PUT 一个 JSON 请求体（热关停规则）。 */
    public JsonNode put(String path, String jsonBody) throws RobotException {
        return send("PUT", path, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    }

    /** POST 一个 JSON 请求体（区服目录 / 公告 / 白名单的运维接口）。 */
    public JsonNode post(String path, String jsonBody) throws RobotException {
        return send("POST", path, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    }

    private JsonNode send(String method, String pathAndQuery) throws RobotException {
        return send(method, pathAndQuery, HttpRequest.BodyPublishers.noBody());
    }

    /** 200 返回应答 JSON；204 返回 null；其余状态抛出。 */
    private JsonNode send(String method, String pathAndQuery, HttpRequest.BodyPublisher body) throws RobotException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(dataUrl + pathAndQuery)).timeout(timeout)
                .header("X-Xm-Admin-Token", token).header("X-Xm-Operator", OPERATOR)
                .header("Content-Type", "application/json")
                .method(method, body).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new RobotException("xm-data 运维接口 " + method + " " + pathAndQuery + " 返回 "
                        + response.statusCode() + "：" + response.body());
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new RobotException("调用 xm-data 运维接口失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-data 被中断", e);
        }
    }

    /** 抓某服务管理端口的 Prometheus 文本（配合 {@link #sum} 取值）。 */
    public String scrape(String metricsBaseUrl) throws RobotException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(metricsBaseUrl + "/actuator/prometheus"))
                .timeout(timeout).GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new RobotException("抓指标失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("抓指标被中断", e);
        }
    }

    /** Prometheus 文本里名为 {@code metric}、标签包含全部 {@code labels}（如 {@code currency_type="1"}）的序列之和。 */
    public static double sum(String text, String metric, String... labels) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(metric) + "\\{([^}]*)} ([0-9.E+-]+)$").matcher(text);
        double total = 0;
        while (m.find()) {
            boolean all = true;
            for (String label : labels) {
                if (!(m.group(1).startsWith(label) || m.group(1).contains("," + label))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                total += Double.parseDouble(m.group(2));
            }
        }
        return total;
    }
}
