package com.game.robot.client;

import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * xm-trade 播种接口的 HTTP 客户端（trade-spec §4.8 方案 A、§5.8；基线 robot 是 gRPC 直连 {@code TradeAdmin.SeedListing}，
 * trade_smoke_scenario.go:234-279、:736-769）：
 * <ul>
 *   <li>{@code POST /admin/trade/seed-listing}，挂在 xm-trade 管理端口（缺省 18111，只绑本机）；</li>
 *   <li>请求体是 {@code trade.SeedListingRequest} 的 protobuf 二进制（{@value #CONTENT_TYPE}），200 带 {@code SeedListingResponse} 二进制，
 *       业务拒绝（参数非法 1005、卖家无归属区 20001、存储 / 发号故障 1003）写在应答体的 {@code error_message}；</li>
 *   <li>鉴权同 xm-data 运维接口：令牌头 {@code X-Xm-Admin-Token}（令牌同 {@link AdminClient#resolveToken}）+ 操作人 {@code X-Xm-Operator}；</li>
 *   <li>HTTP 状态：令牌没配 503、令牌错 401、缺操作人或请求体不是 SeedListingRequest 400、运行模式不是 dev / test 403。</li>
 * </ul>
 * 不幂等：每次调用都是一条新商品（场景按本轮 nonce 标题与 listing_id 找自己的商品）。
 */
public final class TradeAdminClient {

    public static final String SEED_PATH = "/admin/trade/seed-listing";
    public static final String CONTENT_TYPE = "application/x-protobuf";

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * @param baseUrl xm-trade 管理端口（如 {@code http://127.0.0.1:18111}，不带结尾斜杠）
     * @param token   运维令牌；null 表示没有（{@link #seedListing} 直接报错，不发请求）
     */
    public TradeAdminClient(String baseUrl, String token, Duration timeout) {
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

    /**
     * 造一条商品。HTTP 200 返回应答（是否受理看 {@code error_message}，由调用方判定）；其余状态、连不上、超时、应答体解不开都抛出，
     * 消息里带上按状态码给的排查提示。
     */
    public SeedListingResponse seedListing(SeedListingRequest request) throws RobotException {
        if (!hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-trade 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + SEED_PATH)).timeout(timeout)
                .header("X-Xm-Admin-Token", token).header("X-Xm-Operator", AdminClient.OPERATOR)
                .header("Content-Type", CONTENT_TYPE).header("Accept", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.toByteArray())).build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            throw new RobotException("连不上 xm-trade 播种接口 " + baseUrl + SEED_PATH + "（xm-trade 未起，或 --trade-admin-url 指错了）：" + e, e);
        } catch (HttpTimeoutException e) {
            throw new RobotException("xm-trade 播种接口 " + timeout.toMillis() + " ms 内没有应答：" + e, e);
        } catch (IOException e) {
            throw new RobotException("调用 xm-trade 播种接口失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 xm-trade 播种接口被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new RobotException("xm-trade 播种接口 POST " + SEED_PATH + " 返回 " + response.statusCode()
                    + statusHint(response.statusCode()) + "：" + preview(response.body()));
        }
        try {
            return SeedListingResponse.parseFrom(response.body());
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("xm-trade 播种接口的 200 应答体不是 SeedListingResponse：" + e.getMessage(), e);
        }
    }

    /** 按 HTTP 状态给的排查提示（trade-spec §4.8 的状态映射）。 */
    static String statusHint(int status) {
        return switch (status) {
            case 403 -> "（xm-trade 运行模式不是 dev / test，播种接口关闭；本机切片缺省 XM_RUN_MODE=dev）";
            case 401 -> "（运维令牌不对：robot 的 XM_ADMIN_TOKEN / run/xm-admin-token 与 xm-trade 进程的 XM_ADMIN_TOKEN 不一致）";
            case 503 -> "（xm-trade 进程没配 XM_ADMIN_TOKEN，播种接口一律拒绝）";
            case 400 -> "（缺操作人头 X-Xm-Operator，或请求体不是 SeedListingRequest 的 protobuf 二进制）";
            case 404 -> "（这个端口上没有播种接口：--trade-admin-url 指错了端口，或 xm-trade 版本过旧）";
            default -> "";
        };
    }

    /** 非 200 应答体的前 200 个字符（错误页多是文本 / JSON）。 */
    private static String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
