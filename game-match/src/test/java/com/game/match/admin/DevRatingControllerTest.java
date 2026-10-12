package com.game.match.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.game.common.RunMode;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.rating.FaultyDataSource;
import com.game.match.rating.RatingStore;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.support.MatchModes;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * dev 读评分接口（match-spec §9.10、§15.2 的 DevMatchControllerTest 里「读评分的 JSON 形状」与运行模式那两条；令牌、操作人的鉴权在过滤器的用例里）。
 * 真的 Spring MVC 分发与 JSON 序列化（MockMvc，不起端口），存储是 H2 上的真 {@link RatingStore}。
 */
class DevRatingControllerTest {

    private RatingTestDatabase db;
    private FaultyDataSource ds;
    private RatingStore store;

    @BeforeEach
    void setUp() {
        db = RatingTestDatabase.h2();
        ds = new FaultyDataSource(db.dataSource);
        store = new RatingStore(RatingStore.connections(ds), configId -> 30, new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false)),
                System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private MockMvc mvc(RunMode runMode) {
        return MockMvcBuilders.standaloneSetup(new DevRatingController(store, runMode)).build();
    }

    private MockHttpServletResponse call(RunMode runMode, String pid) throws Exception {
        return mvc(runMode).perform(get("/admin/match/dev/rating/" + pid)).andReturn().getResponse();
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 路径常量() {
        assertThat(DevRatingController.PATH).isEqualTo("/admin/match/dev/rating/{pid}");
        assertThat(DevRatingController.PATH_PREFIX).isEqualTo("/admin/match/dev/rating");
    }

    @Test
    void 有评分行_JSON形状逐字节_玩家号是字符串_评分两位小数_局数是数字() throws Exception {
        db.putRating(12001, 151_600, 1);

        MockHttpServletResponse response = call(RunMode.DEV, "12001");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(body(response)).isEqualTo("{\"player_id\":\"12001\",\"rating\":\"1516.00\",\"games\":1}");
    }

    @Test
    void 没有评分行_回默认1500与0局() throws Exception {
        MockHttpServletResponse response = call(RunMode.TEST, "777");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("{\"player_id\":\"777\",\"rating\":\"1500.00\",\"games\":0}");
    }

    @Test
    void 超过2的63次方的玩家号_按无符号十进制进出() throws Exception {
        db.putRating(-1L, 148_453, 12);
        db.putRating(Long.MIN_VALUE, 5, 4_000_000_000L);

        assertThat(body(call(RunMode.DEV, "18446744073709551615")))
                .isEqualTo("{\"player_id\":\"18446744073709551615\",\"rating\":\"1484.53\",\"games\":12}");
        assertThat(body(call(RunMode.DEV, "9223372036854775808")))
                .as("评分不足 1 分补前导 0；局数超过 2^31 也是原值").isEqualTo("{\"player_id\":\"9223372036854775808\",\"rating\":\"0.05\",\"games\":4000000000}");
    }

    @Test
    void 入账之后读到的就是新评分() throws Exception {
        store.apply(BattleResultEvent.newBuilder().setBattleId(9001).setMatchMode(MatchModes.ONE_V_ONE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(1001))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(1002)).setTotalRounds(3).build());

        assertThat(body(call(RunMode.DEV, "1001"))).isEqualTo("{\"player_id\":\"1001\",\"rating\":\"1484.00\",\"games\":1}");
        assertThat(body(call(RunMode.DEV, "1002"))).isEqualTo("{\"player_id\":\"1002\",\"rating\":\"1516.00\",\"games\":1}");
    }

    @Test
    void 运行模式不是dev或test_一律403_不碰库() throws Exception {
        db.putRating(12001, 151_600, 1);

        MockHttpServletResponse prod = call(RunMode.PROD, "12001");
        MockHttpServletResponse prodBadPid = call(RunMode.PROD, "abc");

        assertThat(prod.getStatus()).isEqualTo(403);
        assertThat(body(prod)).contains("dev / test").doesNotContain("1516");
        assertThat(prodBadPid.getStatus()).as("先判运行模式，再看参数").isEqualTo(403);
        assertThat(ds.connections.get()).isZero();
        assertThat(call(RunMode.TEST, "12001").getStatus()).isEqualTo(200);
        assertThat(call(RunMode.DEV, "12001").getStatus()).isEqualTo(200);
    }

    @Test
    void 玩家号不合法_400_不碰库() throws Exception {
        for (String pid : new String[] {"abc", "0", "-1", "+5", "007", "1.5", "18446744073709551616", "123456789012345678901", "12 3", "１２３"}) {
            MockHttpServletResponse response = call(RunMode.DEV, pid);

            assertThat(response.getStatus()).as("pid=%s", pid).isEqualTo(400);
            assertThat(body(response)).contains("无符号十进制");
        }
        assertThat(ds.connections.get()).isZero();
    }

    @Test
    void 读库失败_500_不把故障读成默认分() throws Exception {
        db.putRating(12001, 151_600, 1);
        ds.failConnection(() -> new SQLException("Communications link failure", "08S01"));

        MockHttpServletResponse response = call(RunMode.DEV, "12001");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).doesNotContain("1500").doesNotContain("rating");
        assertThat(call(RunMode.DEV, "12001").getStatus()).as("库恢复后正常").isEqualTo(200);
    }

    @Test
    void 只接受GET() throws Exception {
        MockHttpServletResponse response = mvc(RunMode.DEV).perform(post("/admin/match/dev/rating/12001")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(ds.connections.get()).isZero();
    }

    @Test
    void 评分的字符串形式() {
        assertThat(DevRatingController.formatRating(150_000)).isEqualTo("1500.00");
        assertThat(DevRatingController.formatRating(151_453)).isEqualTo("1514.53");
        assertThat(DevRatingController.formatRating(100)).isEqualTo("1.00");
        assertThat(DevRatingController.formatRating(7)).isEqualTo("0.07");
        assertThat(DevRatingController.formatRating(0)).isEqualTo("0.00");
    }

    @Test
    void 玩家号的解析() {
        assertThat(DevRatingController.parsePlayerId("1")).isEqualTo(1);
        assertThat(DevRatingController.parsePlayerId("18446744073709551615")).isEqualTo(-1L);
        assertThat(DevRatingController.parsePlayerId("9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(DevRatingController.parsePlayerId("18446744073709551616")).as("越界").isZero();
        assertThat(DevRatingController.parsePlayerId("")).isZero();
        assertThat(DevRatingController.parsePlayerId(null)).isZero();
        assertThat(DevRatingController.parsePlayerId("0")).isZero();
        assertThat(DevRatingController.parsePlayerId("01")).as("前导零不是规范形").isZero();
    }
}
