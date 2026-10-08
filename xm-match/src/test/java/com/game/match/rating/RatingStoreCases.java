package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.MatchProperties;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.rating.RatingStore.Outcome;
import com.game.match.rating.RatingStore.Rating;
import com.game.match.rating.RatingStore.Result;
import com.game.match.rating.RatingStore.StoreException;
import com.game.match.support.MatchModes;
import com.game.proto.BattleActivityContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RatingStore} 的行为用例（match-spec §5.2、§15.2 的 RatingStoreTest），后端由子类给：{@link RatingStoreTest} 跑 H2（缺省执行），
 * {@link RatingStoreSqlTest} 跑真 MySQL（{@code -Dxm.it.mysql}）——同一套断言在两种库上都得成立，替身库不会悄悄漂移。
 * 对照基线 {@code rating_match_test.go:369}（{@code TestApplyBattleResultEloAndIdempotent}）、{@code :435}（{@code …5v5UsesTeamAverage}）、
 * {@code rating_review_fix_test.go:41}（部分写入后的续写——Java 一局一笔事务，对应「中途失败整笔回滚、重投恰好一次」）、{@code :160}（回合打满）。
 */
abstract class RatingStoreCases {

    static final int A_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN_VALUE;
    static final int B_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN_VALUE;
    static final int DRAW = eBattleOutcome.BATTLE_OUTCOME_DRAW_VALUE;
    static final String INSERT_APPLIED = "INSERT INTO match_rating_applied";
    static final String ENSURE_ROW = "INSERT INTO match_rating (";
    static final String LOCK_ROW = "SELECT rating_centi FROM match_rating";
    static final String UPDATE_ROW = "UPDATE match_rating SET";
    static final String SET_DELTA = "UPDATE match_rating_applied";
    static final long NOW = 1_800_000_000_000L;

    protected RatingTestDatabase db;
    protected FaultyDataSource ds;
    protected SimpleMeterRegistry meters;
    protected MatchMetrics metrics;
    protected AtomicLong clock;
    protected List<Long> sleeps;
    protected RatingStore store;

    /** 给本用例一个干净的库（两张表都是空的）。 */
    protected abstract RatingTestDatabase openDatabase();

    /** 用例结束：H2 关掉内存库；真 MySQL 的一次性库留给整个类用完再删。 */
    protected abstract void closeDatabase(RatingTestDatabase database);

    @BeforeEach
    void setUpStore() {
        db = openDatabase();
        ds = new FaultyDataSource(db.dataSource);
        meters = new SimpleMeterRegistry();
        metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
        clock = new AtomicLong(NOW);
        sleeps = new ArrayList<>();
        store = store(new MatchProperties.Rating(null, null, 30, Map.of(7, 300), null));
    }

    @AfterEach
    void tearDownStore() {
        closeDatabase(db);
    }

    protected RatingStore store(MatchProperties.Rating rating) {
        return new RatingStore(RatingStore.connections(ds), rating::drawRoundCapFor, metrics, clock::get, sleeps::add);
    }

    static BattleResultEvent.Builder event(long battleId, int mode, int outcome, List<Long> teamA, List<Long> teamB) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(mode).setOutcomeValue(outcome)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addAllPlayerIds(teamA))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addAllPlayerIds(teamB));
    }

    protected Result apply(BattleResultEvent.Builder event) {
        return store.apply(event.build());
    }

    protected double updates(String mode, String outcome) {
        return meters.get("xm.match.rating.updates").tag("mode", mode).tag("outcome", outcome).counter().count();
    }

    protected double roundCapDraws(String mode) {
        return meters.get("xm.match.rating.round.cap.draws").tag("mode", mode).counter().count();
    }

    /** 入账事务里的语句归类（按 SQL 开头）。 */
    static String kind(String sql) {
        if (sql.startsWith(INSERT_APPLIED)) {
            return "插标记";
        }
        if (sql.startsWith(ENSURE_ROW)) {
            return "补行";
        }
        if (sql.startsWith(LOCK_ROW)) {
            return "加锁读";
        }
        if (sql.startsWith(UPDATE_ROW)) {
            return "写分";
        }
        if (sql.startsWith(SET_DELTA)) {
            return "回填增量";
        }
        return sql;
    }

    private static SQLException outOfRange() {
        return new SQLException("Out of range value for column 'games' at row 1", "22003", 1264);
    }

    private static SQLException deadlock() {
        return new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213);
    }

    private static SQLException lockWaitTimeout() {
        return new SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205);
    }

    private static SQLException connectionLost() {
        return new SQLException("Communications link failure", "08S01", 0);
    }

    // ================================================================ 入账与幂等

    @Test
    void 两个新号的1V1_胜者加16败者减16_补出评分行_标记带增量与时刻() {
        Result result = apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(result).isEqualTo(new Result(Outcome.APPLIED, null, 1_600, false));
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(12002)).hasValueSatisfying(row -> assertThat(row).containsExactly(148_400, 1, NOW));
        assertThat(db.appliedRow(9001)).hasValueSatisfying(row -> assertThat(row).containsExactly(MatchModes.ONE_V_ONE, 1_600, NOW));
        assertThat(db.count("match_rating")).isEqualTo(2);
        assertThat(db.count("match_rating_applied")).isEqualTo(1);
        assertThat(ds.commits.get()).as("一局一笔事务").isEqualTo(1);
        assertThat(ds.rollbacks.get()).isZero();
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(1);
        assertThat(roundCapDraws("MATCH_MODE_1V1")).isZero();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 语句次序_先插标记_再按玩家号升序补行与加锁_最后写分并回填增量() {
        apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12002L), List.of(12001L)));

        assertThat(ds.calls).extracting(call -> kind(call.sql())).containsExactly(
                "插标记", "补行", "补行", "加锁读", "加锁读", "写分", "写分", "回填增量");
        assertThat(ds.params(ENSURE_ROW, 1)).as("补行按玩家号升序，与名单顺序无关").containsExactly(12001L, 12002L);
        assertThat(ds.params(LOCK_ROW, 1)).as("加锁同样升序").containsExactly(12001L, 12002L);
        assertThat(ds.params(UPDATE_ROW, 3)).containsExactly(12001L, 12002L);
        assertThat(db.ratingCenti(12002)).as("名单在前的是 A 队").hasValue(151_600);
        assertThat(db.ratingCenti(12001)).hasValue(148_400);
    }

    @Test
    void 同一局再投递一次_duplicate_评分与局数都不变() {
        apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));
        clock.set(NOW + 5_000);

        Result again = apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));
        // 内容不同但 battle_id 相同：照样按重复处理（以先到的为准）
        Result tampered = apply(event(9001, MatchModes.ONE_V_ONE, B_WIN, List.of(12001L), List.of(12003L)));

        assertThat(again).isEqualTo(new Result(Outcome.DUPLICATE, null, 0, false));
        assertThat(tampered.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(12002)).hasValueSatisfying(row -> assertThat(row).containsExactly(148_400, 1, NOW));
        assertThat(db.ratingRow(12003)).as("重复的局不补行").isEmpty();
        assertThat(db.appliedRow(9001)).hasValueSatisfying(row -> assertThat(row).containsExactly(MatchModes.ONE_V_ONE, 1_600, NOW));
        assertThat(ds.executed(ENSURE_ROW)).as("重复的两次在插标记那一步就停了").isEqualTo(2);
        assertThat(ds.commits.get()).isEqualTo(1);
        assertThat(ds.rollbacks.get()).isEqualTo(2);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "duplicate")).isEqualTo(2);
    }

    @Test
    void 连打三局_胜_平_负_逐分钉住_局数累加() {
        apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));
        Result draw = apply(event(9002, MatchModes.ONE_V_ONE, DRAW, List.of(12001L), List.of(12002L)));
        clock.set(NOW + 60_000);
        Result bWins = apply(event(9003, MatchModes.ONE_V_ONE, B_WIN, List.of(12001L), List.of(12002L)));

        // 1516 对 1484 平局：高分方期望 0.5459，失 1.47 分
        assertThat(draw).isEqualTo(new Result(Outcome.APPLIED, null, -147, false));
        // 1514.53 对 1485.47，B 胜：A 失 17.34 分
        assertThat(bWins).isEqualTo(new Result(Outcome.APPLIED, null, -1_734, false));
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(149_719, 3, NOW + 60_000));
        assertThat(db.ratingRow(12002)).hasValueSatisfying(row -> assertThat(row).containsExactly(150_281, 3, NOW + 60_000));
        assertThat(db.count("match_rating_applied")).isEqualTo(3);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(3);
    }

    @Test
    void 五对五用队伍平均分_同队每人同一个增量_已有的行在原值上累加() {
        db.putRating(12101, 160_000, 7);
        db.putRating(12102, 140_000, 0);

        Result result = apply(event(9101, MatchModes.FIVE_V_FIVE, B_WIN, List.of(12101L, 12102L), List.of(12103L, 12104L)));

        assertThat(result).isEqualTo(new Result(Outcome.APPLIED, null, -1_600, false));
        assertThat(db.ratingRow(12101)).hasValueSatisfying(row -> assertThat(row).containsExactly(158_400, 8, NOW));
        assertThat(db.ratingRow(12102)).hasValueSatisfying(row -> assertThat(row).containsExactly(138_400, 1, NOW));
        assertThat(db.ratingRow(12103)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(12104)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.appliedRow(9101)).hasValueSatisfying(row -> assertThat(row).containsExactly(MatchModes.FIVE_V_FIVE, -1_600, NOW));
        assertThat(updates("MATCH_MODE_5V5", "applied")).isEqualTo(1);
    }

    @Test
    void 十人的5V5_一笔事务里全员落账_评分总和不变() {
        List<Long> teamA = List.of(1L, 4L, 5L, 8L, 9L);
        List<Long> teamB = List.of(2L, 3L, 6L, 7L, 10L);

        Result result = apply(event(9102, MatchModes.FIVE_V_FIVE, A_WIN, teamA, teamB));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        for (long pid : teamA) {
            assertThat(db.ratingRow(pid)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        }
        for (long pid : teamB) {
            assertThat(db.ratingRow(pid)).hasValueSatisfying(row -> assertThat(row).containsExactly(148_400, 1, NOW));
        }
        assertThat(db.sumRatingCenti()).isEqualTo(10 * 150_000L);
        assertThat(ds.params(LOCK_ROW, 1)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(ds.commits.get()).isEqualTo(1);
    }

    @Test
    void 评分下限是0() {
        db.putRating(12201, 500, 3);
        db.putRating(12202, 500, 3);

        apply(event(9201, MatchModes.ONE_V_ONE, B_WIN, List.of(12201L), List.of(12202L)));

        assertThat(db.ratingRow(12201)).hasValueSatisfying(row -> assertThat(row).containsExactly(0, 4, NOW));
        assertThat(db.ratingRow(12202)).hasValueSatisfying(row -> assertThat(row).containsExactly(2_100, 4, NOW));
    }

    @Test
    void 精度_两位小数取偶_存进库里的就是定点整数() {
        // 1600 对 1400 平局：Δ_A = −8.311901…，1591.688… → 1591.69、1408.311… → 1408.31
        db.putRating(12301, 160_000, 0);
        db.putRating(12302, 140_000, 0);

        Result result = apply(event(9301, MatchModes.ONE_V_ONE, DRAW, List.of(12301L), List.of(12302L)));

        assertThat(result.deltaACenti()).isEqualTo(-831);
        assertThat(db.ratingCenti(12301)).hasValue(159_169);
        assertThat(db.ratingCenti(12302)).hasValue(140_831);
        assertThat(db.appliedRow(9301)).hasValueSatisfying(row -> assertThat(row[1]).isEqualTo(-831));
    }

    // ================================================================ 不计分

    @Test
    void PVE_切磋_未结束_队伍异常_一律忽略_不取连接不写任何东西() {
        List<BattleResultEvent.Builder> ignored = List.of(
                event(9401, MatchModes.PVE_TEAM, A_WIN, List.of(12003L), List.of()),
                event(9402, MatchModes.PVE_SOLO, A_WIN, List.of(12003L), List.of(12004L)),
                event(9403, MatchModes.PVP_CHALLENGE, A_WIN, List.of(12003L), List.of(12004L)),
                event(9404, MatchModes.THREE_V_THREE, A_WIN, List.of(12003L), List.of(12004L)),
                event(9405, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_ONGOING_VALUE, List.of(12003L), List.of(12004L)),
                event(9406, MatchModes.ONE_V_ONE, A_WIN, List.of(12003L), List.of()),
                event(9407, 99, A_WIN, List.of(12003L), List.of(12004L)));

        List<Result> results = ignored.stream().map(this::apply).toList();

        assertThat(results).extracting(Result::outcome).containsOnly(Outcome.IGNORED);
        assertThat(results).extracting(Result::ignored).containsExactly(EloRules.Ignore.MODE_NOT_RATED, EloRules.Ignore.MODE_NOT_RATED,
                EloRules.Ignore.MODE_NOT_RATED, EloRules.Ignore.MODE_NOT_RATED, EloRules.Ignore.OUTCOME_NOT_SCORED,
                EloRules.Ignore.TEAMS_MALFORMED, EloRules.Ignore.MODE_NOT_RATED);
        assertThat(ds.connections.get()).as("不计分的局不碰库").isZero();
        assertThat(db.count("match_rating")).isZero();
        assertThat(db.count("match_rating_applied")).isZero();
        assertThat(updates("MATCH_MODE_PVE_TEAM", "ignored")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_PVE_SOLO", "ignored")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_PVP_CHALLENGE", "ignored")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "ignored")).isEqualTo(2);
        assertThat(updates("unknown", "ignored")).as("契约里没有的模式值不进标签").isEqualTo(1);
    }

    @Test
    void 活动局的结果被原字节重发三十次_每条都忽略_始终不碰库() {
        BattleResultEvent activity = event(9501, MatchModes.PVE_TEAM, A_WIN, List.of(12003L, 12004L), List.of())
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL))
                .build();

        for (int i = 0; i < 31; i++) {
            assertThat(store.apply(activity).outcome()).isEqualTo(Outcome.IGNORED);
        }

        assertThat(ds.connections.get()).isZero();
        assertThat(updates("MATCH_MODE_PVE_TEAM", "ignored")).isEqualTo(31);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isZero();
    }

    @Test
    void 忽略的局不占battle_id_之后同号的计分局照常入账() {
        apply(event(9601, MatchModes.PVE_TEAM, A_WIN, List.of(12001L), List.of(12002L)));

        Result result = apply(event(9601, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(db.games(12001)).isEqualTo(1);
    }

    // ================================================================ 回合打满

    @Test
    void 回合打满的胜负局按平局入账_并计一次改判_未打满与被覆盖的副本照胜负() {
        db.putRating(15001, 160_000, 0);
        db.putRating(15002, 140_000, 0);

        Result capped = apply(event(9701, MatchModes.ONE_V_ONE, B_WIN, List.of(15001L), List.of(15002L)).setTotalRounds(30));

        assertThat(capped).isEqualTo(new Result(Outcome.APPLIED, null, -831, true));
        assertThat(db.ratingCenti(15001)).as("按平局只失 8.31，而不是真输的 24.31").hasValue(159_169);
        assertThat(db.ratingCenti(15002)).hasValue(140_831);
        assertThat(roundCapDraws("MATCH_MODE_1V1")).isEqualTo(1);

        db.putRating(15001, 160_000, 0);
        db.putRating(15002, 140_000, 0);
        Result realLoss = apply(event(9702, MatchModes.ONE_V_ONE, B_WIN, List.of(15001L), List.of(15002L)).setTotalRounds(29));

        assertThat(realLoss).isEqualTo(new Result(Outcome.APPLIED, null, -2_431, false));
        assertThat(db.ratingCenti(15001)).hasValue(157_569);
        assertThat(db.ratingCenti(15002)).hasValue(142_431);

        db.putRating(15001, 160_000, 0);
        db.putRating(15002, 140_000, 0);
        Result overridden = apply(event(9703, MatchModes.ONE_V_ONE, B_WIN, List.of(15001L), List.of(15002L)).setTotalRounds(30).setBattleConfigId(7));

        assertThat(overridden.roundCapDraw()).as("副本 7 的阈值是 300").isFalse();
        assertThat(db.ratingCenti(15001)).hasValue(157_569);
        assertThat(roundCapDraws("MATCH_MODE_1V1")).as("只有第一局是改判").isEqualTo(1);
    }

    @Test
    void 回合打满的局重复投递_不重复计改判() {
        apply(event(9711, MatchModes.FIVE_V_FIVE, A_WIN, List.of(1L), List.of(2L)).setTotalRounds(30));

        Result again = apply(event(9711, MatchModes.FIVE_V_FIVE, A_WIN, List.of(1L), List.of(2L)).setTotalRounds(30));

        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(db.ratingCenti(1)).as("两个同分的人打成平局：不变").hasValue(150_000);
        assertThat(db.games(1)).isEqualTo(1);
        assertThat(roundCapDraws("MATCH_MODE_5V5")).isEqualTo(1);
    }

    @Test
    void 阈值配成0_关闭回合打满的判定() {
        store = store(new MatchProperties.Rating(null, null, 0, Map.of(), null));

        Result result = apply(event(9721, MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L)).setTotalRounds(30));

        assertThat(result).isEqualTo(new Result(Outcome.APPLIED, null, -1_600, false));
        assertThat(roundCapDraws("MATCH_MODE_1V1")).isZero();
    }

    // ================================================================ 重复的玩家号（坏数据）

    @Test
    void 同队重复的玩家_平均分按名单算两次_只入账一次_局数只加1() {
        db.putRating(13001, 160_000, 0);
        db.putRating(13002, 140_000, 0);

        Result result = apply(event(9801, MatchModes.FIVE_V_FIVE, A_WIN, List.of(13001L, 13001L), List.of(13002L)));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(db.ratingRow(13001)).hasValueSatisfying(row -> assertThat(row).containsExactly(160_769, 1, NOW));
        assertThat(db.ratingRow(13002)).hasValueSatisfying(row -> assertThat(row).containsExactly(139_231, 1, NOW));
        assertThat(ds.executed(UPDATE_ROW)).as("同一行只 UPDATE 一次").isEqualTo(2);
        assertThat(ds.executed(ENSURE_ROW)).isEqualTo(2);
    }

    @Test
    void 跨队重复的玩家_取首次出现的A队_只入账一次() {
        Result result = apply(event(9802, MatchModes.FIVE_V_FIVE, A_WIN, List.of(13011L, 13012L), List.of(13012L, 13013L)));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(db.ratingRow(13011)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(13012)).as("同时在两队：按 A 队加分，局数只加 1").hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(13013)).hasValueSatisfying(row -> assertThat(row).containsExactly(148_400, 1, NOW));
        assertThat(db.count("match_rating")).isEqualTo(3);
        assertThat(ds.executed(UPDATE_ROW)).isEqualTo(3);
    }

    // ================================================================ 无符号 64 位号

    @Test
    void 超过2的63次方的玩家号与战斗号_按无符号存取_加锁按无符号升序() {
        long top = -1L;                       // 2^64 − 1
        long high = Long.MIN_VALUE + 5;       // 2^63 + 5
        long battle = Long.MIN_VALUE + 9;     // 2^63 + 9

        Result result = apply(event(battle, MatchModes.FIVE_V_FIVE, A_WIN, List.of(top, 7L), List.of(high, 3L)));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(db.ratingCenti(top)).hasValue(151_600);
        assertThat(db.ratingCenti(7)).hasValue(151_600);
        assertThat(db.ratingCenti(high)).hasValue(148_400);
        assertThat(db.ratingCenti(3)).hasValue(148_400);
        assertThat(db.appliedRow(battle)).isPresent();
        assertThat(ds.params(LOCK_ROW, 1)).as("无符号升序：3 < 7 < 2^63+5 < 2^64−1").containsExactly(3L, 7L,
                new BigInteger("9223372036854775813"), new BigInteger("18446744073709551615"));
        assertThat(apply(event(battle, MatchModes.FIVE_V_FIVE, A_WIN, List.of(top, 7L), List.of(high, 3L))).outcome())
                .as("大号的战斗号同样挡得住重复").isEqualTo(Outcome.DUPLICATE);

        Map<Long, Rating> read = store.find(List.of(top, high, 3L, 99L));
        assertThat(read).containsOnlyKeys(top, high, 3L);
        assertThat(read.get(top)).isEqualTo(new Rating(top, 151_600, 1));
        assertThat(read.get(high)).isEqualTo(new Rating(high, 148_400, 1));
    }

    // ================================================================ 失败与重跑

    @Test
    void 事务中途失败_整笔回滚不留任何痕迹_重投后恰好入账一次() {
        ds.failBefore(UPDATE_ROW, RatingStoreCases::connectionLost);

        assertThatThrownBy(() -> apply(event(9901, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))))
                .isInstanceOf(StoreException.class).hasMessageContaining("9901").hasCauseInstanceOf(SQLException.class);

        assertThat(db.count("match_rating_applied")).as("标记随事务回滚").isZero();
        assertThat(db.count("match_rating")).as("补出来的行也回滚").isZero();
        assertThat(ds.commits.get()).isZero();
        assertThat(ds.executed(INSERT_APPLIED)).as("不是死锁：不重跑").isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "error")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isZero();
        assertThat(sleeps).isEmpty();

        Result retried = apply(event(9901, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(retried.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(12002)).hasValueSatisfying(row -> assertThat(row).containsExactly(148_400, 1, NOW));
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(1);
    }

    @Test
    void 回填增量时失败_已写的评分一并回滚() {
        db.putRating(12001, 160_000, 4);
        ds.failBefore(SET_DELTA, RatingStoreCases::connectionLost);

        assertThatThrownBy(() -> apply(event(9902, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)))).isInstanceOf(StoreException.class);

        assertThat(ds.executed(UPDATE_ROW)).as("两个人的评分都已经 UPDATE 过").isEqualTo(2);
        assertThat(db.ratingRow(12001)).as("回滚到赛前").hasValueSatisfying(row -> assertThat(row).containsExactly(160_000, 4, 0));
        assertThat(db.ratingRow(12002)).isEmpty();
        assertThat(db.appliedRow(9902)).isEmpty();
    }

    @Test
    void 提交结果不明_其实已提交_重投得到duplicate_评分只加一次() {
        ds.failCommit(true, RatingStoreCases::connectionLost);

        assertThatThrownBy(() -> apply(event(9903, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)))).isInstanceOf(StoreException.class);
        Result retried = apply(event(9903, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(retried.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.ratingRow(12002)).hasValueSatisfying(row -> assertThat(row).containsExactly(148_400, 1, NOW));
        assertThat(updates("MATCH_MODE_1V1", "error")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "duplicate")).isEqualTo(1);
    }

    @Test
    void 提交失败且没有提交_重投后入账() {
        ds.failCommit(false, RatingStoreCases::connectionLost);

        assertThatThrownBy(() -> apply(event(9904, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)))).isInstanceOf(StoreException.class);
        assertThat(db.count("match_rating_applied")).isZero();

        assertThat(apply(event(9904, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(db.games(12001)).isEqualTo(1);
    }

    @Test
    void 死锁_整笔重跑_第二次成功_只入账一次() {
        ds.failBefore(LOCK_ROW, RatingStoreCases::deadlock);

        Result result = apply(event(9905, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(result).isEqualTo(new Result(Outcome.APPLIED, null, 1_600, false));
        assertThat(ds.executed(INSERT_APPLIED)).as("整笔重跑：标记插了两次（第一次随事务回滚）").isEqualTo(2);
        assertThat(ds.rollbacks.get()).isEqualTo(1);
        assertThat(ds.commits.get()).isEqualTo(1);
        assertThat(sleeps).as("重跑之前等一小会儿").hasSize(1);
        assertThat(sleeps.get(0)).isBetween(20L, 49L);
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, NOW));
        assertThat(db.count("match_rating_applied")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "error")).isZero();
    }

    @Test
    void 锁等待超时两次_第三次成功() {
        ds.failBefore(ENSURE_ROW, RatingStoreCases::lockWaitTimeout);
        ds.failBefore(UPDATE_ROW, RatingStoreCases::lockWaitTimeout);

        Result result = apply(event(9906, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(ds.executed(INSERT_APPLIED)).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(1)).as("第二次等得更久").isBetween(40L, 69L);
        assertThat(db.games(12001)).isEqualTo(1);
        assertThat(db.games(12002)).isEqualTo(1);
    }

    @Test
    void 连续三次死锁_放弃并抛错_库里不留东西_最后一次之后不再等() {
        ds.failBefore(LOCK_ROW, RatingStoreCases::deadlock);
        ds.failBefore(LOCK_ROW, RatingStoreCases::deadlock);
        ds.failBefore(LOCK_ROW, RatingStoreCases::deadlock);

        assertThatThrownBy(() -> apply(event(9907, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))))
                .isInstanceOf(StoreException.class)
                .satisfies(e -> assertThat(RatingSqlErrors.isRetryable(e)).as("原因是死锁：消费者按可恢复故障暂停重试").isTrue())
                .satisfies(e -> assertThat(RatingSqlErrors.isDataError(e)).isFalse());

        assertThat(ds.executed(INSERT_APPLIED)).isEqualTo(RatingStore.MAX_ATTEMPTS);
        assertThat(sleeps).hasSize(RatingStore.MAX_ATTEMPTS - 1);
        assertThat(db.count("match_rating_applied")).isZero();
        assertThat(db.count("match_rating")).isZero();
        assertThat(updates("MATCH_MODE_1V1", "error")).as("一条结果只计一次 error").isEqualTo(1);

        assertThat(apply(event(9907, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))).outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void 取不到连接_抛错且可重试() {
        ds.failConnection(RatingStoreCases::connectionLost);

        assertThatThrownBy(() -> apply(event(9908, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))))
                .isInstanceOf(StoreException.class)
                .satisfies(e -> assertThat(RatingSqlErrors.isDataError(e)).isFalse());

        assertThat(updates("MATCH_MODE_1V1", "error")).isEqualTo(1);
        assertThat(apply(event(9908, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))).outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void 库拒绝这条数据_是数据错误_不重跑_整笔回滚() {
        db.putRating(12001, 150_000, 6);
        ds.failBefore(UPDATE_ROW, RatingStoreCases::outOfRange);

        assertThatThrownBy(() -> apply(event(9909, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))))
                .isInstanceOf(StoreException.class)
                .satisfies(e -> assertThat(RatingSqlErrors.isDataError(e)).as("消费者据此写毒丸日志后跳过").isTrue())
                .satisfies(e -> assertThat(RatingSqlErrors.isRetryable(e)).isFalse());

        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(150_000, 6, 0));
        assertThat(db.ratingRow(12002)).as("对手的行与标记一并回滚").isEmpty();
        assertThat(db.count("match_rating_applied")).isZero();
        assertThat(ds.executed(INSERT_APPLIED)).as("数据错误不重跑").isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(updates("MATCH_MODE_1V1", "error")).isEqualTo(1);
    }

    // ================================================================ 读

    @Test
    void 读评分_没有行的不在结果里_单读回落新号_重复的玩家号只查一次() {
        db.putRating(12001, 162_525, 9);
        db.putRating(12002, 0, 1);

        Map<Long, Rating> rows = store.find(List.of(12001L, 12003L, 12002L, 12001L));

        assertThat(rows).containsOnlyKeys(12001L, 12002L);
        assertThat(rows.get(12001L)).isEqualTo(new Rating(12001, 162_525, 9));
        assertThat(rows.get(12002L)).as("0 分是合法的评分，不是「没有行」").isEqualTo(new Rating(12002, 0, 1));
        assertThat(ds.calls).hasSize(1);
        assertThat(ds.calls.get(0).params()).as("去重后按首次出现的顺序").containsExactly(12001L, 12003L, 12002L);

        assertThat(store.findOrFresh(12003)).isEqualTo(new Rating(12003, 150_000, 0));
        assertThat(store.findOrFresh(12001)).isEqualTo(new Rating(12001, 162_525, 9));
        assertThat(ds.commits.get()).as("读不开事务").isZero();
    }

    @Test
    void 读评分_空名单不取连接_超过一批的名单分批查() {
        assertThat(store.find(List.of())).isEmpty();
        assertThat(ds.connections.get()).isZero();

        List<Long> many = new ArrayList<>();
        for (long pid = 1; pid <= RatingStore.READ_CHUNK + 3; pid++) {
            many.add(pid);
        }
        db.putRating(2, 151_600, 1);
        db.putRating(RatingStore.READ_CHUNK + 2, 148_400, 1);

        Map<Long, Rating> rows = store.find(many);

        assertThat(rows).containsOnlyKeys(2L, (long) RatingStore.READ_CHUNK + 2);
        assertThat(ds.calls).as("两批、一条连接").hasSize(2);
        assertThat(ds.calls.get(0).params()).hasSize(RatingStore.READ_CHUNK);
        assertThat(ds.calls.get(1).params()).hasSize(3);
        assertThat(ds.connections.get()).isEqualTo(1);
    }

    @Test
    void 读评分失败_抛错而不是回落缺省值() {
        ds.failConnection(RatingStoreCases::connectionLost);
        assertThatThrownBy(() -> store.find(List.of(1L))).isInstanceOf(StoreException.class).hasCauseInstanceOf(SQLException.class);

        ds.failBefore("SELECT player_id", RatingStoreCases::connectionLost);
        assertThatThrownBy(() -> store.findOrFresh(1)).isInstanceOf(StoreException.class);
    }

    // ================================================================ 清理

    @Test
    void 清理入账标记_只删早于给定时刻的_一批不超过上限_评分行不动() {
        apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));
        for (long battle = 100; battle < 105; battle++) {
            db.putApplied(battle, NOW - 10_000 + battle);
        }

        int first = store.deleteAppliedBefore(NOW - 1, 3);
        int second = store.deleteAppliedBefore(NOW - 1, 3);
        int third = store.deleteAppliedBefore(NOW - 1, 3);

        assertThat(first).isEqualTo(3);
        assertThat(second).isEqualTo(2);
        assertThat(third).isZero();
        assertThat(db.appliedRow(9001)).as("刚入账的标记（applied_at = NOW）不早于截止时刻").isPresent();
        assertThat(db.count("match_rating_applied")).isEqualTo(1);
        assertThat(db.count("match_rating")).isEqualTo(2);
        assertThat(store.deleteAppliedBefore(NOW, 3)).as("截止时刻是开区间：等于它的不删").isZero();
        assertThat(store.deleteAppliedBefore(NOW + 1, 3)).isEqualTo(1);
    }

    @Test
    void 清理失败_抛错() {
        ds.failBefore("DELETE FROM match_rating_applied", RatingStoreCases::connectionLost);

        assertThatThrownBy(() -> store.deleteAppliedBefore(NOW, 10)).isInstanceOf(StoreException.class);
    }
}
