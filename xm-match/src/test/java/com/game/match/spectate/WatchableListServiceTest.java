package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 164 可观战列表的流程（spectate-spec §3.3、§4.5、§10.3 的 {@code WatchableListServiceTest} 一段）：条数收口、最新在前、懒剔除（非法成员 / 过期 /
 * 落点不在）且不回填、读记录出错与损坏只跳过、读索引失败回「索引不可用」、摘要字段原样、剔除在应答组好之后异步发出。
 * 对照基线 {@code spectate_test.go:206}（NewestFirstAndEvictsOrphans）、{@code :582}（ClampsLimit）、{@code :782}（EvictsStaleAndSummarylessRecords——
 * 「空壳记录」在 Java 是损坏的落点：跳过、<b>不</b>剔除，W15）、{@code :854}（PropagatesRedisError）。
 *
 * <p>存储是内存替身（与落点替身、手拨的 Redis 时间共享状态）：它的异步剔除缺省在调用线程当场生效，所以「剔除了什么」可以在调用返回后直接看；
 * 「异步」这件事本身由挂起 {@code evictAsync} 的那两条用例钉。
 */
class WatchableListServiceTest {

    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final long STALE_MS = 360_000;
    private static final int ONE_V_ONE = MatchMode.MATCH_MODE_1V1_VALUE;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), placements, events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final WatchableListService service = new WatchableListService(store, metrics);

    private static Deadline d() {
        return Deadline.after(2_000);
    }

    private static BattlePlacement placement(long battleId, long createdAtMs, String... names) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-7").setRpcHost("127.0.0.1")
                .setRpcPort(21207).setAttempt(1).setMode(ONE_V_ONE).setBattleConfigId(0).addAllPlayerNames(List.of(names))
                .setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000).build();
    }

    /** 一场已公开的战斗：落点记录 + 索引成员（分数 = created_at_ms）。 */
    private void register(long battleId, long createdAtMs, String... names) {
        placements.put(placement(battleId, createdAtMs, names));
        store.putWatchable(battleId, createdAtMs);
    }

    private ListWatchableBattlesResponse list(int limit) {
        WatchableListService.Result result = service.list(limit, d());
        assertThat(result).isInstanceOf(WatchableListService.Result.Listed.class);
        return ((WatchableListService.Result.Listed) result).response();
    }

    private static List<Long> ids(ListWatchableBattlesResponse response) {
        return response.getBattlesList().stream().map(BattleWatchSummary::getBattleId).toList();
    }

    private double listed(String result) {
        return meters.get("xm.match.list.watchable").tags("result", result).counter().count();
    }

    private double evicted(String reason) {
        return meters.get("xm.match.watchable.index.evictions").tags("reason", reason).counter().count();
    }

    private double anomalies(String reason) {
        return meters.get("xm.match.watchable.anomalies").tags("reason", reason).counter().count();
    }

    // ================================================================ 次序、条数、懒剔除

    @Test
    void 最新开局在前_落点已不在的孤儿成员不出现并被剔除_limit生效() {
        register(880030, T0 - 3_000, "老场");
        register(880031, T0 - 2_000, "中场");
        register(880032, T0 - 1_000, "新场");
        store.putWatchable(880033, T0); // 孤儿：索引里有、落点记录已过期

        ListWatchableBattlesResponse all = list(0);

        assertThat(ids(all)).as("created_at 降序，孤儿不出现").containsExactly(880032L, 880031L, 880030L);
        assertThat(store.watchable()).as("孤儿成员已被剔除").containsExactly("880032", "880031", "880030");
        assertThat(placements.stored(880033)).isEmpty();
        assertThat(evicted("missing_record")).isEqualTo(1.0);
        assertThat(store.calls).as("一次读索引、一次批读这一页的落点（按索引的次序）、一次异步剔除").containsExactly("list(20)",
                "readPlacements([880033, 880032, 880031, 880030])", "evictAsync([Missing[battleId=880033]])");

        ListWatchableBattlesResponse limited = list(2);

        assertThat(ids(limited)).containsExactly(880032L, 880031L);
        assertThat(listed("ok")).isEqualTo(2.0);
        assertThat(listed("error")).isZero();
    }

    @Test
    void 条数收口_0走默认20条_超过上限夹到50_负数是大于2的31次方的uint32同样夹到50_其余原样() {
        for (int i = 0; i < 55; i++) {
            register(881_000 + i, T0 - i);
        }

        assertThat(list(150).getBattlesCount()).as("超上限的 limit 必须被夹住").isEqualTo(50);
        assertThat(list(0).getBattlesCount()).as("limit = 0 走默认条数").isEqualTo(20);
        assertThat(list(-1).getBattlesCount()).as("0xFFFFFFFF").isEqualTo(50);
        assertThat(list(50).getBattlesCount()).isEqualTo(50);
        assertThat(list(51).getBattlesCount()).isEqualTo(50);
        assertThat(ids(list(3))).as("取的是最新的三场").containsExactly(881_000L, 881_001L, 881_002L);
        assertThat(store.calls.stream().filter(call -> call.startsWith("list(")).toList())
                .as("收口在读索引之前：从不向存储要超过 50 条").containsExactly("list(50)", "list(20)", "list(50)", "list(50)", "list(50)", "list(3)");
        assertThat(store.watchable()).as("都是好成员：什么都没剔除").hasSize(55);
    }

    @Test
    void 分数过期的成员连同落点一起剔除_落点损坏的只跳过不剔除并计异常() {
        register(880230, T0, "好场");
        register(880231, T0 - STALE_MS - 1, "过期场"); // 落点还在，但分数已出 360 s 的窗口
        register(880232, T0 - 500, "坏场");
        placements.corrupt(880232); // 基线的「空壳记录」在 Java 的对应物：落点在但读不成一条好记录

        ListWatchableBattlesResponse response = list(0);

        assertThat(ids(response)).containsExactly(880230L);
        assertThat(store.watchable()).as("过期的摘掉；损坏的留着（W15：不为一条在场的落点做删除）").containsExactly("880230", "880232");
        assertThat(placements.stored(880231)).as("过期成员的落点一并删除").isEmpty();
        assertThat(events).contains("placement.evict:880231", "spectate.evict:880231");
        assertThat(placements.stored(880232)).as("损坏的落点原样留着：179 的定位不能被列表毁掉").isPresent();
        assertThat(placements.corrupted(880232)).isTrue();
        assertThat(store.calls).as("过期成员不读落点；损坏的不进剔除名单").containsExactly("list(20)", "readPlacements([880230, 880232])",
                "evictAsync([Stale[battleId=880231, cutoffMs=" + (T0 - STALE_MS) + "]])");
        assertThat(evicted("stale")).isEqualTo(1.0);
        assertThat(evicted("missing_record")).isZero();
        assertThat(anomalies("corrupt_record")).isEqualTo(1.0);
        assertThat(listed("ok")).isEqualTo(1.0);
    }

    @Test
    void 过期分界_分数恰在分界上的还算数_早一毫秒的才剔除_分界取存储给的Redis时间() {
        clock.advanceSeconds(1_000); // 分界跟着 Redis 时间走，不是固定值
        long now = clock.peekMs();
        register(880240, now - STALE_MS, "恰在分界上");
        register(880241, now - STALE_MS - 1, "早一毫秒");

        ListWatchableBattlesResponse response = list(0);

        assertThat(ids(response)).containsExactly(880240L);
        assertThat(store.watchable()).containsExactly("880240");
        assertThat(store.calls).contains("evictAsync([Stale[battleId=880241, cutoffMs=" + (now - STALE_MS) + "]])");
    }

    @Test
    void 非法成员按原串剔除_不读它的落点_带前导零的也算非法() {
        register(880250, T0 - 10, "好场");
        store.putWatchable("abc", T0);
        store.putWatchable("007", T0 - 1); // 当成 7 号战斗的话，按战斗号的剔除摘的是 "7"，这个成员永远留着
        store.putWatchable("0", T0 - 2);
        placements.put(placement(7, T0, "七号"));

        ListWatchableBattlesResponse response = list(0);

        assertThat(ids(response)).containsExactly(880250L);
        assertThat(store.watchable()).containsExactly("880250");
        assertThat(placements.stored(7)).as("非法成员的剔除不碰任何落点").isPresent();
        assertThat(store.calls).containsExactly("list(20)", "readPlacements([880250])",
                "evictAsync([Invalid[member=abc], Invalid[member=007], Invalid[member=0]])");
        assertThat(evicted("invalid_member")).isEqualTo(3.0);
    }

    @Test
    void 不回填_这一页剔除之后列表可以短于limit_后面的好场次留给下一次请求() {
        register(880260, T0 - 1, "一");
        store.putWatchable(880261, T0 - 2); // 孤儿
        register(880262, T0 - 3, "二");
        register(880263, T0 - 4, "三");

        ListWatchableBattlesResponse first = list(3);

        assertThat(ids(first)).as("前三名里有一个孤儿：只回两条，不拿第四名补").containsExactly(880260L, 880262L);

        assertThat(ids(list(3))).as("下一次请求自然补齐").containsExactly(880260L, 880262L, 880263L);
    }

    @Test
    void 同分的场次按成员字符串降序_与基线在Redis里排的次序一致() {
        register(9, T0, "九");
        register(10, T0, "十");
        register(880270, T0 - 1, "晚一毫秒");

        assertThat(ids(list(0))).as("\"9\" 的字典序大于 \"10\"").containsExactly(9L, 10L, 880270L);
    }

    // ================================================================ 摘要

    @Test
    void 摘要从落点原样映射_名字是gather的成员顺序_不认识的模式保留数值_created_at取落点里的不是索引分数() {
        long battleId = 0xF000_0000_0000_0001L; // 无符号 64 位：高位为 1
        BattlePlacement stored = BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(3).setBattleInstanceId("inst-3")
                .setRpcHost("10.0.0.3").setRpcPort(21203).setAttempt(2).setMode(99).setBattleConfigId(7).addPlayerNames("丙").addPlayerNames("甲")
                .addPlayerNames("乙").setCreatedAtMs(T0 - 5_000).setDeadlineMs(T0 + 295_000).build();
        placements.put(stored);
        store.putWatchable(battleId, T0 - 4_000); // 分数与落点里的时间故意不同
        register(880280, T0 - 9_000, "发起者", "应战者");

        ListWatchableBattlesResponse response = list(0);

        assertThat(response.getBattlesList()).hasSize(2);
        BattleWatchSummary summary = response.getBattles(0);
        assertThat(summary.getBattleId()).isEqualTo(battleId);
        assertThat(Long.toUnsignedString(summary.getBattleId())).isEqualTo("17293822569102704641");
        assertThat(summary.getModeValue()).as("契约里没有的模式值原样下发，不折成 0").isEqualTo(99);
        assertThat(summary.getMode()).isEqualTo(MatchMode.UNRECOGNIZED);
        assertThat(summary.getBattleConfigId()).isEqualTo(7);
        assertThat(summary.getPlayerNamesList()).as("顺序 = gather 的成员顺序，不排序").containsExactly("丙", "甲", "乙");
        assertThat(summary.getCreatedAtMs()).as("客户端用它算已开局时长：取落点里的 Unix 毫秒").isEqualTo(T0 - 5_000);
        assertThat(summary).as("只有这五个字段：落点里的节点、地址、attempt 不下发")
                .isEqualTo(BattleWatchSummary.newBuilder().setBattleId(battleId).setModeValue(99).setBattleConfigId(7).addPlayerNames("丙")
                        .addPlayerNames("甲").addPlayerNames("乙").setCreatedAtMs(T0 - 5_000).build());
        assertThat(response.getBattles(1)).isEqualTo(BattleWatchSummary.newBuilder().setBattleId(880280).setMode(MatchMode.MATCH_MODE_1V1)
                .addPlayerNames("发起者").addPlayerNames("应战者").setCreatedAtMs(T0 - 9_000).build());
    }

    @Test
    void 索引为空_回空列表_应答体是0字节_不批读也不剔除() {
        ListWatchableBattlesResponse response = list(6);

        assertThat(response.getBattlesList()).isEmpty();
        assertThat(response.toByteString().isEmpty()).as("全默认值的应答 = 0 字节；客户端靠这个空包把面板从「刷新中」收回").isTrue();
        assertThat(store.calls).containsExactly("list(6)");
        assertThat(listed("ok")).isEqualTo(1.0);
    }

    @Test
    void 这一页全是要剔除的成员_同样回空列表_没有候选就不批读() {
        store.putWatchable("garbage", T0);
        store.putWatchable(880290, T0 - STALE_MS - 5);

        ListWatchableBattlesResponse response = list(0);

        assertThat(response.toByteString().isEmpty()).isTrue();
        assertThat(store.calls).containsExactly("list(20)", "evictAsync([Invalid[member=garbage], Stale[battleId=880290, cutoffMs=" + (T0 - STALE_MS) + "]])");
        assertThat(store.watchable()).isEmpty();
    }

    // ================================================================ 故障

    @Test
    void 读索引失败_回索引不可用_不能吞成空列表_不读落点不剔除_出口计error() {
        register(880320, T0, "好场");
        store.faults.failNext("list");

        WatchableListService.Result result = service.list(0, d());

        assertThat(result).as("空列表在客户端等同于「当前没有可观战的战斗」：基础设施故障不能伪装成它")
                .isEqualTo(new WatchableListService.Result.IndexUnavailable());
        assertThat(store.calls).containsExactly("list(20)");
        assertThat(store.watchable()).containsExactly("880320");
        assertThat(listed("error")).isEqualTo(1.0);
        assertThat(listed("ok")).isZero();
        assertThat(anomalies("record_read_failed")).isZero();
    }

    @Test
    void 请求截止已过_读索引即失败_同样回索引不可用() {
        register(880321, T0, "好场");

        WatchableListService.Result result = service.list(0, Deadline.after(0));

        assertThat(result).isInstanceOf(WatchableListService.Result.IndexUnavailable.class);
        assertThat(listed("error")).isEqualTo(1.0);
    }

    @Test
    void 批读落点整批失败_这一页每条都按读记录出错_回变短的列表而不是索引不可用_都不剔除_计record_read_failed() {
        register(880330, T0, "好场一");
        register(880331, T0 - 1, "好场二");
        store.putWatchable(880332, T0 - 2); // 孤儿：这次读不出「不在」，所以不能剔除
        store.putWatchable("bad", T0 - 3);
        register(880333, T0 - STALE_MS - 9, "过期场");
        store.faults.failNext("readPlacements");

        WatchableListService.Result result = service.list(0, d());

        assertThat(result).isInstanceOf(WatchableListService.Result.Listed.class);
        ListWatchableBattlesResponse response = ((WatchableListService.Result.Listed) result).response();
        assertThat(response.getBattlesList()).as("读不出记录的都跳过：这一次是空列表").isEmpty();
        assertThat(store.watchable()).as("读记录出错不剔除（下次可能恢复）；只凭成员与分数就能定的两种照常剔除")
                .containsExactly("880330", "880331", "880332");
        assertThat(store.calls).containsExactly("list(20)", "readPlacements([880330, 880331, 880332])",
                "evictAsync([Invalid[member=bad], Stale[battleId=880333, cutoffMs=" + (T0 - STALE_MS) + "]])");
        assertThat(anomalies("record_read_failed")).as("整批算一次").isEqualTo(1.0);
        assertThat(evicted("missing_record")).isZero();
        assertThat(listed("ok")).as("照常回了列表：出口是 ok").isEqualTo(1.0);
        assertThat(listed("error")).isZero();

        assertThat(ids(list(0))).as("下一次请求读得出来了：好场次回来，孤儿这时才剔除").containsExactly(880330L, 880331L);
        assertThat(store.watchable()).containsExactly("880330", "880331");
    }

    @Test
    void 存储违约抛出别的异常_原样交给派发器_出口仍只计一次error() {
        register(880340, T0, "好场");
        IllegalStateException bug = new IllegalStateException("存储的 bug");
        store.faults.failNext("list", bug);

        assertThatThrownBy(() -> service.list(0, d())).isSameAs(bug);

        assertThat(listed("error")).isEqualTo(1.0);
        assertThat(listed("ok")).isZero();
    }

    // ================================================================ 剔除是异步的、有条件的

    @Test
    void 剔除在应答组好之后才发出_剔除挂住时应答照常返回_放行后才生效_指标按发出计() {
        register(880350, T0, "好场");
        store.putWatchable(880351, T0 - 1); // 孤儿
        store.putWatchable("junk", T0 - 2);
        store.hang("evictAsync");

        ListWatchableBattlesResponse response = list(0);

        assertThat(ids(response)).containsExactly(880350L);
        assertThat(store.watchable()).as("剔除还没生效：应答不等它").containsExactly("880350", "880351", "junk");
        assertThat(store.calls).as("剔除排在读索引、批读之后，而且只发一批").containsExactly("list(20)", "readPlacements([880350, 880351])",
                "evictAsync([Invalid[member=junk], Missing[battleId=880351]])");
        assertThat(evicted("missing_record")).as("异步剔除等不到结果：按发出计").isEqualTo(1.0);
        assertThat(evicted("invalid_member")).isEqualTo(1.0);
        assertThat(listed("ok")).isEqualTo(1.0);

        store.resume("evictAsync");

        assertThat(store.watchable()).containsExactly("880350");
    }

    @Test
    void 落点不在的剔除只摘成员_批读之后才预写的落点不会被删_那个成员也留着() {
        store.putWatchable(880360, T0 - 1); // 批读时落点不在
        store.hang("evictAsync");

        assertThat(ids(list(0))).isEmpty();

        // 剔除落地之前，gather 为这一场预写了落点（一场正在建房的战斗）
        BattlePlacement rewritten = placement(880360, T0, "新局");
        placements.put(rewritten);
        store.resume("evictAsync");

        assertThat(placements.stored(880360)).as("读到「不在」之后才写的记录不能删").contains(rewritten);
        assertThat(store.watchable()).as("条件在存储里原子复核：落点此刻在，成员不摘").containsExactly("880360");
        assertThat(ids(list(0))).as("下一次请求它就是一条好场次").containsExactly(880360L);
    }

    @Test
    void 每个请求恰好记一个出口() {
        register(880370, T0, "好场");

        list(0);
        list(1);
        store.faults.failNext("list");
        service.list(0, d());
        store.faults.failNext("readPlacements");
        service.list(0, d());

        assertThat(listed("ok")).isEqualTo(3.0);
        assertThat(listed("error")).isEqualTo(1.0);
        assertThat(listed("overloaded")).as("过载由处理器记").isZero();
    }
}
