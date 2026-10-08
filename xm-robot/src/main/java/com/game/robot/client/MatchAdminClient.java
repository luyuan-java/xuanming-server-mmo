package com.game.robot.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * xm-match dev / test 管理接口的 HTTP 客户端（match-spec §9.10、§15.5；挂在 xm-match 管理端口，缺省 18113，只绑本机）：
 * <ul>
 *   <li>{@code GET /admin/match/dev/rating/{pid}}：读评分，200 带 JSON {@code {"player_id":"…","rating":"1516.00","games":1}}
 *       （uint64 的玩家号是十进制<b>字符串</b>，评分是两位小数的字符串；没有行的玩家是 1500.00 / 0）；</li>
 *   <li>{@code POST /admin/match/dev/activity-battle}：帮会活动开战，请求体是契约 {@code match.StartActivityBattleRequest} 的 protobuf 二进制
 *       （{@value #CONTENT_TYPE}），200 带 {@code StartActivityBattleResponse} 二进制；业务拒绝一律 200，写在应答的 {@code reject} 里；</li>
 *   <li>鉴权同 xm-trade 播种接口：令牌头 {@code X-Xm-Admin-Token}（令牌同 {@link AdminClient#resolveToken}）+ 操作人 {@code X-Xm-Operator}；</li>
 *   <li>HTTP 状态：令牌没配 503、令牌错 401、缺操作人 / 玩家号不合法 / 请求体不对 400、运行模式不是 dev / test 403、读评分时库故障 500。</li>
 * </ul>
 * 评分在 robot 里一律用 centi（× 100 的整数）：{@code "1516.00"} → 151600，免得用浮点比较。
 */
public final class MatchAdminClient {

    public static final String RATING_PATH = "/admin/match/dev/rating/";
    public static final String ACTIVITY_BATTLE_PATH = "/admin/match/dev/activity-battle";
    public static final String CONTENT_TYPE = "application/x-protobuf";
    /** 新号（评分表里没有行）的评分：1500.00。 */
    public static final long DEFAULT_RATING_CENTI = 150_000;

    /** 严格解析：JSON 里的整数一律按 BigInteger 读（uint64 过不了 long 的上半区），小数按 BigDecimal 读（不经 double）。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final BigInteger UINT64_MAX = new BigInteger("18446744073709551615");

    /**
     * 一名玩家的评分。
     *
     * @param playerId    玩家号（uint64 的位模式）
     * @param ratingCenti 评分 × 100（1500.00 = 150000）
     * @param games       计分的局数
     */
    public record Rating(long playerId, long ratingCenti, long games) {

        /** 两位小数的评分串（151600 → {@code 1516.00}）。 */
        public String ratingText() {
            return BigDecimal.valueOf(ratingCenti, 2).toPlainString();
        }

        @Override
        public String toString() {
            return "rating=" + ratingText() + " games=" + games;
        }
    }

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * @param baseUrl xm-match 管理端口（如 {@code http://127.0.0.1:18113}，不带结尾斜杠）
     * @param token   运维令牌；null 表示没有（调用直接报错，不发请求）
     */
    public MatchAdminClient(String baseUrl, String token, Duration timeout) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeout = timeout;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    /** 读一名玩家的评分。200 才返回；其余状态、连不上、超时、应答体不合形状都抛出（消息里带按状态码给的排查提示）。 */
    public Rating rating(long playerId) throws RobotException {
        String path = RATING_PATH + Long.toUnsignedString(playerId);
        HttpRequest request = authorized(path).header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response = send(request, "GET " + path);
        if (response.statusCode() != 200) {
            throw new RobotException("xm-match 读评分接口 GET " + path + " 返回 " + response.statusCode() + statusHint(response.statusCode())
                    + "：" + preview(response.body()));
        }
        return parseRating(playerId, new String(response.body(), StandardCharsets.UTF_8));
    }

    /**
     * 帮会活动开战（走与 xm-guild 将来经 Dubbo 调的同一个实现）。HTTP 200 返回应答（受理与否看 {@code reject}，由调用方判定）；
     * 其余状态、连不上、超时、应答体解不开都抛出。
     */
    public StartActivityBattleResponse startActivityBattle(StartActivityBattleRequest request) throws RobotException {
        HttpRequest httpRequest = authorized(ACTIVITY_BATTLE_PATH)
                .header("Content-Type", CONTENT_TYPE).header("Accept", CONTENT_TYPE + ", text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray())).build();
        HttpResponse<byte[]> response = send(httpRequest, "POST " + ACTIVITY_BATTLE_PATH);
        if (response.statusCode() != 200) {
            throw new RobotException("xm-match 活动开战接口 POST " + ACTIVITY_BATTLE_PATH + " 返回 " + response.statusCode()
                    + statusHint(response.statusCode()) + "：" + preview(response.body()));
        }
        try {
            return StartActivityBattleResponse.parseFrom(response.body());
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-match 活动开战接口的 200 应答体不是 StartActivityBattleResponse：" + e.getMessage(), e);
        }
    }

    /** 抓 xm-match 管理端口的 Prometheus 文本（配合 {@link AdminClient#sum} 取值；actuator 不过运维令牌）。 */
    public String scrapeMetrics() throws RobotException {
        String path = "/actuator/prometheus";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).GET().build();
        HttpResponse<byte[]> response = send(request, "GET " + path);
        if (response.statusCode() != 200) {
            throw new RobotException("抓 xm-match 指标返回 " + response.statusCode() + "（--match-admin-url 指错了端口？）");
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * 解读评分接口的 200 应答体。形状按 match-spec §9.10：{@code player_id} 是十进制字符串、{@code rating} 是至多两位小数的字符串、
     * {@code games} 是非负整数；为了不把「数字写成了字符串 / 字符串写成了数字」这类形状差异误报成评分不对，三个字段都同时认字符串与 JSON 数字，
     * 但取值必须合法：{@code player_id} 等于请求的玩家号，评分非负且恰好能表示成 centi，局数非负。
     */
    static Rating parseRating(long requestedPlayerId, String json) throws RobotException {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RobotException("xm-match 读评分接口的 200 应答体不是 JSON：" + preview(json.getBytes(StandardCharsets.UTF_8)), e);
        }
        if (root == null || !root.isObject()) {
            throw new RobotException("xm-match 读评分接口的 200 应答体不是 JSON 对象：" + preview(json.getBytes(StandardCharsets.UTF_8)));
        }
        BigInteger playerId = integer(root, "player_id", json);
        if (playerId.signum() <= 0 || playerId.compareTo(UINT64_MAX) > 0) {
            throw new RobotException("xm-match 读评分接口的 player_id 不是 1 – 2^64−1 的无符号十进制：" + json);
        }
        if (playerId.longValue() != requestedPlayerId) {
            throw new RobotException("xm-match 读评分接口回的是 player_id=" + playerId + "，请求的是 " + Long.toUnsignedString(requestedPlayerId));
        }
        BigDecimal rating = decimal(root, "rating", json);
        long ratingCenti;
        try {
            ratingCenti = rating.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new RobotException("xm-match 读评分接口的 rating 不是至多两位小数的评分：" + json, e);
        }
        if (ratingCenti < 0) {
            throw new RobotException("xm-match 读评分接口的 rating 为负（下限是 0）：" + json);
        }
        BigInteger games = integer(root, "games", json);
        if (games.signum() < 0 || games.bitLength() > 63) {
            throw new RobotException("xm-match 读评分接口的 games 不是非负整数：" + json);
        }
        return new Rating(requestedPlayerId, ratingCenti, games.longValue());
    }

    /** 按 HTTP 状态给的排查提示（match-spec §9.10 的鉴权与状态映射）。 */
    static String statusHint(int status) {
        return switch (status) {
            case 403 -> "（xm-match 运行模式不是 dev / test，dev 管理口关闭；本机切片缺省 XM_RUN_MODE=dev）";
            case 401 -> "（运维令牌不对：robot 的 XM_ADMIN_TOKEN / run/xm-admin-token 与 xm-match 进程的 XM_ADMIN_TOKEN 不一致）";
            case 503 -> "（xm-match 进程没配 XM_ADMIN_TOKEN，管理口一律拒绝）";
            case 400 -> "（缺操作人头 X-Xm-Operator、玩家号不是无符号十进制，或请求体不是对应的 protobuf 二进制）";
            case 404 -> "（这个端口上没有 match 的 dev 管理口：--match-admin-url 指错了端口，或 xm-match 版本过旧）";
            case 500 -> "（xm-match 内部错误：读评分时数据库故障不回落缺省值，看 xm-match 日志）";
            default -> "";
        };
    }

    private HttpRequest.Builder authorized(String path) throws RobotException {
        if (!hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-match 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("X-Xm-Admin-Token", token).header("X-Xm-Operator", AdminClient.OPERATOR);
    }

    private HttpResponse<byte[]> send(HttpRequest request, String what) throws RobotException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            throw new RobotException("连不上 xm-match 管理端口 " + baseUrl + "（" + what + "；xm-match 未起，或 --match-admin-url 指错了）：" + e, e);
        } catch (HttpTimeoutException e) {
            throw new RobotException("xm-match 管理端口 " + timeout.toMillis() + " ms 内没有应答（" + what + "）：" + e, e);
        } catch (IOException e) {
            throw new RobotException("调用 xm-match 管理端口失败（" + what + "）：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-match 管理端口被中断（" + what + "）", e);
        }
    }

    /** 字段是整数（JSON 整数，或只含数字的字符串）；缺失或别的形状即失败。 */
    private static BigInteger integer(JsonNode root, String field, String json) throws RobotException {
        JsonNode node = root.get(field);
        if (node != null && node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node != null && node.isTextual() && node.textValue().matches("[0-9]{1,20}")) {
            return new BigInteger(node.textValue());
        }
        throw new RobotException("xm-match 读评分接口的应答缺 " + field + "，或它不是非负整数：" + json);
    }

    /** 字段是十进制数（JSON 数字，或十进制字符串）；缺失或别的形状即失败。 */
    private static BigDecimal decimal(JsonNode root, String field, String json) throws RobotException {
        JsonNode node = root.get(field);
        if (node != null && node.isNumber()) {
            return node.decimalValue();
        }
        if (node != null && node.isTextual() && node.textValue().matches("-?[0-9]{1,18}(\\.[0-9]{1,6})?")) {
            return new BigDecimal(node.textValue());
        }
        throw new RobotException("xm-match 读评分接口的应答缺 " + field + "，或它不是十进制数：" + json);
    }

    /** 非 200 应答体的前 200 个字符（错误多是 UTF-8 文本 / JSON）。 */
    private static String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
