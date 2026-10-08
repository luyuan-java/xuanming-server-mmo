package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateRules.Mark;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.MatchMode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 观战的纯函数（spectate-spec §10.2）：建房窗口的五个分支（对照基线 {@code watchbattle_create_window_test.go:55-174}）、列表条数收口、
 * 过期分界、落点 → 摘要（对照 {@code spectate_test.go:582}、{@code :809}）、标记值的编解码与索引成员的规范写法。
 */
class SpectateRulesTest {

    private static final long WINDOW = MatchBudgets.GATHER_CREATE_STAGE_WORST_MS;
    private static final long NOW = 1_800_000_000_000L;
    /** ≥ 2^63 的战斗号：无符号十进制是 9223372036854775885。 */
    private static final long BIG = Long.MIN_VALUE + 77;
    private static final String BIG_TEXT = "9223372036854775885";

    // ================================================================ 建房窗口

    @Test
    void 建房窗口_已公开_房间在公开前就已建成_窗口内也不算在建() {
        assertThat(SpectateRules.roomMayBeCreating(true, NOW, NOW)).isFalse();
        assertThat(SpectateRules.roomMayBeCreating(true, NOW, NOW + 1_000)).isFalse();
    }

    @Test
    void 建房窗口_created_at为0_不该出现的旧记录_按窗口外() {
        assertThat(SpectateRules.roomMayBeCreating(false, 0, NOW)).isFalse();
        assertThat(SpectateRules.roomMayBeCreating(false, 0, 0)).isFalse();
    }

    @Test
    void 建房窗口_未公开且在窗口内_可能在建_恰在上沿也算() {
        assertThat(WINDOW).as("窗口 = gather 从写落点到最后一次建房的最坏耗时").isEqualTo(22_200);
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, NOW)).as("刚写入").isTrue();
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, NOW + WINDOW - 2_000)).as("接近上沿").isTrue();
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, NOW + WINDOW)).as("恰在上沿：基线是「超过」才出窗").isTrue();
    }

    @Test
    void 建房窗口_未公开但出了窗口_gather必已放弃_不算在建() {
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, NOW + WINDOW + 1)).isFalse();
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, NOW + WINDOW + 1_000)).isFalse();
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, NOW + 300_000)).isFalse();
    }

    @Test
    void 建房窗口_检查时刻早于创建时刻_时钟源有偏差_按窗口内() {
        assertThat(SpectateRules.roomMayBeCreating(false, NOW + 5_000, NOW)).isTrue();
        assertThat(SpectateRules.roomMayBeCreating(false, NOW + 5_000_000, NOW)).as("差得再多也是：宁可晚剔除").isTrue();
    }

    @Test
    void 建房窗口_时刻按无符号比较() {
        long huge = -1_000L; // 作为无符号数极大
        assertThat(SpectateRules.roomMayBeCreating(false, NOW, huge)).as("检查时刻（无符号）远晚于创建时刻：出窗").isFalse();
        assertThat(SpectateRules.roomMayBeCreating(false, huge, NOW)).as("创建时刻（无符号）晚于检查时刻：窗口内").isTrue();
    }

    // ================================================================ 列表条数与过期分界

    @Test
    void 列表条数收口_0取缺省20_超过50取50_其余原样_负数是大于2的31次方的uint32() {
        assertThat(SpectateRules.clampLimit(0)).isEqualTo(20);
        assertThat(SpectateRules.clampLimit(1)).isEqualTo(1);
        assertThat(SpectateRules.clampLimit(6)).as("Unity 面板一次拉 6 条").isEqualTo(6);
        assertThat(SpectateRules.clampLimit(20)).isEqualTo(20);
        assertThat(SpectateRules.clampLimit(50)).isEqualTo(50);
        assertThat(SpectateRules.clampLimit(51)).isEqualTo(50);
        assertThat(SpectateRules.clampLimit(Integer.MAX_VALUE)).isEqualTo(50);
        assertThat(SpectateRules.clampLimit(-1)).as("0xFFFFFFFF").isEqualTo(50);
        assertThat(SpectateRules.clampLimit(Integer.MIN_VALUE)).isEqualTo(50);
    }

    @Test
    void 过期分界是存储时间减360秒_分数严格小于分界才算过期() {
        assertThat(SpectateRules.staleCutoff(NOW)).isEqualTo(NOW - 360_000);
        assertThat(SpectateRules.stale(NOW - 360_001, NOW)).isTrue();
        assertThat(SpectateRules.stale(NOW - 360_000, NOW)).as("恰在分界上：还算活着").isFalse();
        assertThat(SpectateRules.stale(NOW, NOW)).isFalse();
        assertThat(SpectateRules.stale(NOW + 5_000, NOW)).as("分数比现在还新（时钟偏差）").isFalse();
    }

    // ================================================================ 落点 → 摘要

    @Test
    void 摘要从落点原样映射_名字保持成员顺序_不带节点地址之类的内部字段() {
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(BIG).setBattleNodeId(3).setBattleInstanceId("inst").setRpcHost("10.0.0.3")
                .setRpcPort(21200).setAttempt(2).setMode(MatchMode.MATCH_MODE_5V5_VALUE).setBattleConfigId(7)
                .addAllPlayerNames(List.of("丙", "甲", "乙", "甲")).setCreatedAtMs(NOW).setDeadlineMs(NOW + 300_000).build();

        BattleWatchSummary summary = SpectateRules.summaryOf(placement);

        assertThat(Long.toUnsignedString(summary.getBattleId())).isEqualTo(BIG_TEXT);
        assertThat(summary.getMode()).isEqualTo(MatchMode.MATCH_MODE_5V5);
        assertThat(summary.getBattleConfigId()).isEqualTo(7);
        assertThat(summary.getPlayerNamesList()).as("顺序 = gather 的成员顺序，重名也不去重").containsExactly("丙", "甲", "乙", "甲");
        assertThat(summary.getCreatedAtMs()).as("客户端拿它算已开局时长：原样").isEqualTo(NOW);
        assertThat(summary).isEqualTo(BattleWatchSummary.newBuilder().setBattleId(BIG).setMode(MatchMode.MATCH_MODE_5V5).setBattleConfigId(7)
                .addAllPlayerNames(List.of("丙", "甲", "乙", "甲")).setCreatedAtMs(NOW).build());
    }

    @Test
    void 摘要_契约里没有的mode数值原样保留_不折成0() throws Exception {
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(77).setMode(4242).setCreatedAtMs(NOW).build();

        BattleWatchSummary summary = SpectateRules.summaryOf(placement);

        assertThat(summary.getModeValue()).isEqualTo(4242);
        assertThat(summary.getMode()).isEqualTo(MatchMode.UNRECOGNIZED);
        assertThat(BattleWatchSummary.parser().parseFrom(summary.toByteArray()).getModeValue()).as("过一遍线上的字节仍在").isEqualTo(4242);
        assertThat(summary.getPlayerNamesList()).isEmpty();
    }

    // ================================================================ 索引成员

    @Test
    void 索引成员是无符号十进制_解析只认规范形() {
        assertThat(SpectateRules.member(77)).isEqualTo("77");
        assertThat(SpectateRules.member(BIG)).isEqualTo(BIG_TEXT);
        assertThat(SpectateRules.member(-1L)).isEqualTo("18446744073709551615");

        assertThat(SpectateRules.parseMember("77")).hasValue(77);
        assertThat(SpectateRules.parseMember(BIG_TEXT)).hasValue(BIG);
        assertThat(SpectateRules.parseMember("18446744073709551615")).hasValue(-1L);
        for (long id : new long[] {1, 77, Long.MAX_VALUE, Long.MIN_VALUE, BIG, -1L}) {
            assertThat(SpectateRules.parseMember(SpectateRules.member(id))).as("往返 %s", Long.toUnsignedString(id)).hasValue(id);
        }
    }

    @Test
    void 索引成员_不是规范形的一律解析为空_按非法成员剔除() {
        for (String bad : List.of("", "0", "007", "+7", "-1", " 7", "7 ", "7.0", "1e3", "abc", "7a", "18446744073709551616",
                "99999999999999999999", "184467440737095516150", "７７", "0x4d", "77:abcd")) {
            assertThat(SpectateRules.parseMember(bad)).as("'%s'", bad).isEmpty();
        }
        assertThat(SpectateRules.parseMember(null)).isEmpty();
    }

    // ================================================================ 观战标记

    @Test
    void 标记值是战斗号冒号nonce_编解码往返_大号按无符号十进制() {
        String value = SpectateRules.encodeMark(BIG, "0123456789abcdef");

        assertThat(value).isEqualTo(BIG_TEXT + ":0123456789abcdef");
        assertThat(SpectateRules.decodeMark(value)).contains(new Mark(BIG, "0123456789abcdef"));
        assertThat(SpectateRules.decodeMark("77:ffffffffffffffff")).contains(new Mark(77, "ffffffffffffffff"));
        assertThat(SpectateRules.decodeMark(SpectateRules.encodeMark(-1L, "0000000000000000")).orElseThrow().battleId()).isEqualTo(-1L);
    }

    @Test
    void 标记值_解析不了的都是脏值_基线那种只有战斗号的旧值也算() {
        for (String bad : List.of("", "77", "77:", ":0123456789abcdef", "0:0123456789abcdef", "077:0123456789abcdef", "77:0123456789abcde",
                "77:0123456789abcdef0", "77:0123456789ABCDEF", "77:0123456789abcdeg", "77:0123:456789abcdef", "abc:0123456789abcdef",
                "-1:0123456789abcdef", "18446744073709551616:0123456789abcdef", " 77:0123456789abcdef", "77 :0123456789abcdef")) {
            assertThat(SpectateRules.decodeMark(bad)).as("'%s'", bad).isEmpty();
        }
        assertThat(SpectateRules.decodeMark(null)).isEmpty();
    }

    @Test
    void 编码时战斗号为0或nonce形状不对是调用方的错() {
        assertThatThrownBy(() -> SpectateRules.encodeMark(0, "0123456789abcdef")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpectateRules.encodeMark(77, "abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpectateRules.encodeMark(77, "0123456789ABCDEF")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpectateRules.encodeMark(77, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonce是16位小写十六进制_每次不同_能直接用来编码() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String nonce = SpectateRules.newNonce();
            assertThat(nonce).hasSize(SpectateRules.NONCE_LENGTH).matches("[0-9a-f]{16}");
            seen.add(nonce);
            assertThat(SpectateRules.decodeMark(SpectateRules.encodeMark(77, nonce))).contains(new Mark(77, nonce));
        }
        assertThat(seen).as("64 位随机数：2000 次不该撞").hasSize(2_000);
        assertThat(SpectateRules.NONCE_LENGTH).isEqualTo(16);
    }

    // ================================================================ 清退原因与预算

    @Test
    void 清退原因的三个串逐字钉住() {
        assertThat(SpectateRules.REASON_REWATCH).isEqualTo("rewatch");
        assertThat(SpectateRules.REASON_ENTER_GATHER).isEqualTo("enter_gather");
        assertThat(SpectateRules.REASON_CONCURRENT_QUEUE).isEqualTo("concurrent_queue");
    }

    @Test
    void 留出预留的硬截止_剩余减预留_不够减就是已过的截止() {
        Deadline request = Deadline.after(4_500);

        Deadline rewatchStop = SpectateRules.reserveBefore(request, MatchBudgets.WATCH_REWATCH_RESERVE_MS);
        Deadline addStop = SpectateRules.reserveBefore(request, MatchBudgets.WATCH_ADD_RESERVE_MS);

        // 上界是硬保证（剩余只会变小）；下界留 1 s 的余量，不靠窄时间窗
        assertThat(rewatchStop.remainingMillis()).as("4500 − 1200").isBetween(2_300L, 3_300L);
        assertThat(addStop.remainingMillis()).as("4500 − 200").isBetween(3_300L, 4_300L);
        assertThat(rewatchStop.remainingMillis()).isLessThan(request.remainingMillis());
        assertThat(SpectateRules.reserveBefore(Deadline.after(60_000), 59_000).remainingMillis()).as("预留从剩余里减，不是从 0 起算")
                .isLessThanOrEqualTo(1_000L);
        assertThat(SpectateRules.reserveBefore(Deadline.after(150), 200).expired()).as("剩余不足预留：已过").isTrue();
        assertThat(SpectateRules.reserveBefore(Deadline.after(0), 0).expired()).isTrue();
        assertThat(SpectateRules.reserveBefore(request, 0).remainingMillis()).isBetween(3_000L, 4_500L);
        assertThatThrownBy(() -> SpectateRules.reserveBefore(request, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 一跳的超时是基线超时与硬截止剩余里较小的那个() {
        assertThat(SpectateRules.hopTimeout(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS, Deadline.after(10_000))).as("预算充裕：基线的 3 s")
                .isEqualTo(Duration.ofSeconds(3));
        Duration clipped = SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, Deadline.after(800));
        assertThat(clipped.toMillis()).as("硬截止只剩 800 ms：超时不会比它长").isLessThanOrEqualTo(800L);
        assertThat(SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, Deadline.after(60_000)).toMillis())
                .as("硬截止远在后面：不会比基线超时长").isEqualTo(3_000L);
        assertThat(SpectateRules.hopTimeout(3_000, Deadline.after(0))).isEqualTo(Duration.ZERO);
    }

    @Test
    void 满预算的163_换场与登记的两跳都夹在gate的5秒之内() {
        Deadline request = Deadline.after(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
        Duration remove = SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS,
                SpectateRules.reserveBefore(request, MatchBudgets.WATCH_REWATCH_RESERVE_MS));
        // 最坏：换场的 Remove 用满它的窗口才返回，之后立刻发 Add
        long afterRemoveMs = MatchBudgets.DEFAULT_REQUEST_BUDGET_MS - remove.toMillis();
        long addWindowMs = afterRemoveMs - MatchBudgets.WATCH_ADD_RESERVE_MS;

        // 上界是硬保证；下界留 1 s 的余量，不靠窄时间窗（4500 − 1200 只比 3000 多 300 ms：恰好等于 3 s 要求这几行在 300 ms 内跑完）。
        // 「满预算时用得满 3 s」的算术由 xm-api 的 MatchBudgetsTest「观战不等式五」纯函数地钉住
        assertThat(remove.toMillis()).as("满预算时换场的 Remove 用得满（或接近）3 s，不会更长")
                .isBetween(2_000L, MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS);
        assertThat(afterRemoveMs).as("Remove 之后仍过得了 Add 的门槛").isGreaterThanOrEqualTo(MatchBudgets.WATCH_ADD_MIN_BUDGET_MS);
        assertThat(addWindowMs).as("Add 至少有这么久").isGreaterThanOrEqualTo(MatchBudgets.WATCH_ADD_MIN_BUDGET_MS - MatchBudgets.WATCH_ADD_RESERVE_MS);
        assertThat(remove.toMillis() + addWindowMs + MatchBudgets.WATCH_ADD_RESERVE_MS).as("两跳都带硬截止：加起来不超过请求预算")
                .isLessThanOrEqualTo(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS).isLessThan(5_000);
    }
}
