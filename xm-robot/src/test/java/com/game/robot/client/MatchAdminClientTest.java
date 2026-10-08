package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.robot.client.MatchAdminClient.Rating;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * xm-match dev 管理口客户端对一个本机假管理端口：请求形状（方法、路径、令牌、操作人、protobuf 体）、读评分的 JSON 解析（uint64 是字符串、
 * 评分换成 centi）、活动开战的 protobuf 字节往返、各状态码的提示、没有令牌不发请求。
 */
class MatchAdminClientTest {

    /** 大于 Long.MAX_VALUE 的玩家号（uint64 的上半区）。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> responseBody = new AtomicReference<>(new byte[0]);
    private final List<Captured> captured = new ArrayList<>();

    private record Captured(String method, String path, String token, String operator, String contentType, byte[] body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (captured) {
                captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Xm-Admin-Token"), exchange.getRequestHeaders().getFirst("X-Xm-Operator"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), body));
            }
            byte[] out = responseBody.get();
            exchange.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- 读评分

    @Test
    void 读评分_GET带令牌与操作人_路径里的玩家号是无符号十进制_评分换成centi() throws Exception {
        json("{\"player_id\":\"9223372036854775809\",\"rating\":\"1516.00\",\"games\":1}");

        Rating rating = client("tok").rating(BIG);

        assertThat(rating).isEqualTo(new Rating(BIG, 151_600, 1));
        assertThat(rating.ratingText()).isEqualTo("1516.00");
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("GET");
            assertThat(c.path()).isEqualTo("/admin/match/dev/rating/9223372036854775809").startsWith(MatchAdminClient.RATING_PATH);
            assertThat(c.token()).isEqualTo("tok");
            assertThat(c.operator()).isEqualTo(AdminClient.OPERATOR);
            assertThat(c.body()).isEmpty();
        });
    }

    @Test
    void 读评分的JSON_新号是1500与0局_不足1分与大局数都按原值() throws Exception {
        assertThat(MatchAdminClient.parseRating(777, "{\"player_id\":\"777\",\"rating\":\"1500.00\",\"games\":0}"))
                .isEqualTo(new Rating(777, MatchAdminClient.DEFAULT_RATING_CENTI, 0));
        assertThat(MatchAdminClient.parseRating(1001, "{\"player_id\":\"1001\",\"rating\":\"1484.53\",\"games\":12}"))
                .isEqualTo(new Rating(1001, 148_453, 12));
        assertThat(MatchAdminClient.parseRating(-1L, "{\"player_id\":\"18446744073709551615\",\"rating\":\"0.05\",\"games\":4000000000}"))
                .isEqualTo(new Rating(-1L, 5, 4_000_000_000L));
        assertThat(new Rating(1, 5, 0).ratingText()).isEqualTo("0.05");
        assertThat(new Rating(1, 0, 0).ratingText()).isEqualTo("0.00");
    }

    @Test
    void 读评分的JSON_数字与字符串两种写法都认_取值一样() throws Exception {
        // 规格的形状是「玩家号、评分是字符串，局数是数字」；写法不同不该被误报成评分不对
        Rating expected = new Rating(BIG, 151_650, 3);
        assertThat(MatchAdminClient.parseRating(BIG, "{\"player_id\":9223372036854775809,\"rating\":1516.5,\"games\":\"3\"}")).isEqualTo(expected);
        assertThat(MatchAdminClient.parseRating(BIG, "{\"games\":3,\"rating\":\"1516.5\",\"player_id\":\"9223372036854775809\",\"extra\":true}"))
                .isEqualTo(expected);
        assertThat(MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":1516,\"games\":1}").ratingCenti()).isEqualTo(151_600);
    }

    @Test
    void 读评分的JSON_形状或取值不对即失败_写明是哪个字段() {
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "not json")).isInstanceOf(RobotException.class).hasMessageContaining("不是 JSON");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "[1]")).hasMessageContaining("不是 JSON 对象");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "")).hasMessageContaining("不是 JSON 对象");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"rating\":\"1500.00\",\"games\":0}")).hasMessageContaining("player_id");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"games\":0}")).hasMessageContaining("rating");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":\"1500.00\"}")).hasMessageContaining("games");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"6\",\"rating\":\"1500.00\",\"games\":0}"))
                .as("回的不是请求的那个人").hasMessageContaining("player_id=6").hasMessageContaining("请求的是 5");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"0\",\"rating\":\"1500.00\",\"games\":0}"))
                .hasMessageContaining("无符号十进制");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"18446744073709551616\",\"rating\":\"1500.00\",\"games\":0}"))
                .as("超过 uint64").hasMessageContaining("无符号十进制");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"-5\",\"rating\":\"1500.00\",\"games\":0}"))
                .hasMessageContaining("player_id");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":\"1500.005\",\"games\":0}"))
                .as("不能恰好表示成 centi").hasMessageContaining("两位小数");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":\"-1.00\",\"games\":0}")).hasMessageContaining("为负");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":\"abc\",\"games\":0}")).hasMessageContaining("rating");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":\"1500.00\",\"games\":-1}")).hasMessageContaining("games");
        assertThatThrownBy(() -> MatchAdminClient.parseRating(5, "{\"player_id\":\"5\",\"rating\":\"1500.00\",\"games\":1.5}")).hasMessageContaining("games");
    }

    @Test
    void 读评分_非200按状态码给提示_应答体附在后面() {
        status.set(403);
        responseBody.set("dev 管理口只在运行模式 dev / test 下开放".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client("tok").rating(5)).isInstanceOf(RobotException.class).hasMessageContaining("返回 403")
                .hasMessageContaining("XM_RUN_MODE=dev").hasMessageContaining("只在运行模式");
        status.set(500);
        responseBody.set("读评分失败（数据库故障）".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client("tok").rating(5)).hasMessageContaining("返回 500").hasMessageContaining("不回落缺省值")
                .hasMessageContaining("数据库故障");
        assertThat(MatchAdminClient.statusHint(401)).contains("令牌不对");
        assertThat(MatchAdminClient.statusHint(503)).contains("没配 XM_ADMIN_TOKEN");
        assertThat(MatchAdminClient.statusHint(400)).contains("X-Xm-Operator");
        assertThat(MatchAdminClient.statusHint(404)).contains("--match-admin-url");
        assertThat(MatchAdminClient.statusHint(418)).isEmpty();
    }

    // ---------------------------------------------------------------- 活动开战

    @Test
    void 活动开战_POST契约请求的protobuf字节_200解出应答_字节往返不走样() throws Exception {
        StartActivityBattleResponse reply = StartActivityBattleResponse.newBuilder().setBattleId(BIG).build();
        responseBody.set(reply.toByteArray());
        StartActivityBattleRequest request = StartActivityBattleRequest.newBuilder().setBattleConfigId(1).addMemberPlayerIds(BIG).addMemberPlayerIds(7)
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(-2L)
                        .setActivityId(9).setPeriodKey(20261008).setInitiatorPlayerId(BIG).setGuildPeriodKey(20261008))
                .build();

        StartActivityBattleResponse got = client("tok").startActivityBattle(request);

        assertThat(got).isEqualTo(reply);
        assertThat(got.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.path()).isEqualTo(MatchAdminClient.ACTIVITY_BATTLE_PATH).isEqualTo("/admin/match/dev/activity-battle");
            assertThat(c.token()).isEqualTo("tok");
            assertThat(c.operator()).isEqualTo(AdminClient.OPERATOR);
            assertThat(c.contentType()).isEqualTo("application/x-protobuf");
            assertThat(c.body()).as("请求体就是契约消息的序列化字节").isEqualTo(request.toByteArray());
            assertThat(StartActivityBattleRequest.parseFrom(c.body())).isEqualTo(request);
        });
    }

    @Test
    void 活动开战_业务拒绝在200应答体里_由调用方判定() throws Exception {
        responseBody.set(StartActivityBattleResponse.newBuilder().setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE)
                .setOffenderPlayerId(BIG).build().toByteArray());
        StartActivityBattleResponse got = client("tok").startActivityBattle(StartActivityBattleRequest.getDefaultInstance());
        assertThat(got.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE);
        assertThat(got.getOffenderPlayerId()).isEqualTo(BIG);
        assertThat(got.getBattleId()).isZero();
    }

    @Test
    void 活动开战_全默认的应答体是0字节_解成reject为NONE且battle_id为0_场景据此判没受理() throws Exception {
        // 0 字节是合法的 protobuf（全默认值）：客户端不能把它当成功——场景要求 battle_id ≠ 0
        StartActivityBattleResponse got = client("tok").startActivityBattle(StartActivityBattleRequest.getDefaultInstance());
        assertThat(got).isEqualTo(StartActivityBattleResponse.getDefaultInstance());
        assertThat(got.getBattleId()).isZero();
    }

    @Test
    void 活动开战_非200带提示_应答体解不开即失败() {
        status.set(401);
        assertThatThrownBy(() -> client("bad").startActivityBattle(StartActivityBattleRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("返回 401").hasMessageContaining("令牌不对");
        status.set(200);
        responseBody.set(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertThatThrownBy(() -> client("tok").startActivityBattle(StartActivityBattleRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("不是 StartActivityBattleResponse");
    }

    // ---------------------------------------------------------------- 指标与公共

    @Test
    void 抓指标_不带运维令牌也能抓_取值配合AdminClient的sum() throws Exception {
        responseBody.set(("# HELP xm_match_gathers_total x\n"
                + "xm_match_gathers_total{mode=\"MATCH_MODE_1V1\",outcome=\"success\"} 3.0\n"
                + "xm_match_gathers_total{mode=\"MATCH_MODE_PVE_SOLO\",outcome=\"success\"} 2.0\n"
                + "xm_match_gathers_total{mode=\"MATCH_MODE_1V1\",outcome=\"internal\"} 9.0\n").getBytes(StandardCharsets.UTF_8));

        String text = client(null).scrapeMetrics();

        assertThat(AdminClient.sum(text, "xm_match_gathers_total", "outcome=\"success\"")).isEqualTo(5.0);
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("GET");
            assertThat(c.path()).isEqualTo("/actuator/prometheus");
            assertThat(c.token()).as("actuator 不过运维令牌").isNull();
        });
        status.set(404);
        assertThatThrownBy(() -> client("tok").scrapeMetrics()).isInstanceOf(RobotException.class).hasMessageContaining("404");
    }

    @Test
    void 没有令牌不发管理口请求() {
        assertThat(client(null).hasToken()).isFalse();
        assertThat(client("").hasToken()).isFalse();
        assertThat(client("tok").hasToken()).isTrue();
        assertThatThrownBy(() -> client(null).rating(5)).isInstanceOf(RobotException.class).hasMessageContaining("XM_ADMIN_TOKEN")
                .hasMessageContaining("run/xm-admin-token");
        assertThatThrownBy(() -> client("").startActivityBattle(StartActivityBattleRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("XM_ADMIN_TOKEN");
        assertThat(captured).isEmpty();
    }

    @Test
    void 连不上给出xm_match未起的提示() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        MatchAdminClient down = new MatchAdminClient("http://127.0.0.1:" + closedPort, "tok", Duration.ofSeconds(10));
        assertThat(down.baseUrl()).isEqualTo("http://127.0.0.1:" + closedPort);
        assertThatThrownBy(() -> down.rating(5)).isInstanceOf(RobotException.class).hasMessageContaining("xm-match");
    }

    private void json(String body) {
        responseBody.set(body.getBytes(StandardCharsets.UTF_8));
    }

    private MatchAdminClient client(String token) {
        return new MatchAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), token, Duration.ofSeconds(5));
    }
}
