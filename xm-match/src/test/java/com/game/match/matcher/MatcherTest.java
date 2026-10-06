package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.match.gather.FailPolicy;
import com.game.match.gather.GatherPlan;
import com.game.match.metrics.MatchMetrics.MatcherRound;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.PopResult;
import com.game.proto.match.MatchMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 凑单的组件测试（match-spec §2.5–§2.9、§9.5、§15.2 的 MatcherTest 一行；对照基线 {@code nobattle_guard_test.go}、{@code review_fix_test.go}、
 * {@code ticket_cas_test.go:279-354}）：票据存储用内存实现，玩家状态、开局管线、battle 目录、发号租约都是替身。钉的是凑单自己的判定——
 * 三种暂停、抢不到锁、成员校验的每个出口、弹组被拒后的重挑与上限、弹组结局不明、依赖故障、成员顺序、两个 gauge 的口径。
 * 移植自基线的端到端用例在 {@link MatcherPortedScenarios}（内存与真 Redis 各跑一遍）。
 */
@ExtendWith(OutputCaptureExtension.class)
class MatcherTest {

    private static final QueueRef Q1V1 = new QueueRef(3, 0);
    private static final QueueRef Q5V5 = new QueueRef(1, 0);
    private static final QueueRef PVE1 = new QueueRef(5, 1);
    private static final long TTL = MatcherRig.TICKET_TTL_MS;

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore memory = new InMemoryTicketStore(clock);
    private final HookedTicketStore store = new HookedTicketStore(memory);
    private final MatcherRig rig = new MatcherRig(id -> id >= 1 && id <= 3);
    private final QueueMatcher matcher = rig.matcher(store, MatcherRig.defaults());

    private static Deadline d() {
        return MatcherRig.d();
    }

    /** 上线并排进队列（票号 {@code t-<pid>}）。 */
    private void join(long playerId, QueueRef queue, long ratingCenti) {
        rig.players.online(playerId, 1, 7);
        assertThat(memory.enqueue(playerId, "t-" + playerId, queue, 1, ratingCenti, TTL, d())).isInstanceOf(TicketStore.JoinResult.Created.class);
    }

    private void join(long playerId, QueueRef queue) {
        join(playerId, queue, 150_000);
    }

    private MatcherRound round() {
        return matcher.runRound(() -> false);
    }

    private TicketState stateOf(long playerId) {
        return memory.ticketOf(playerId).map(Ticket::state).orElse(null);
    }

    private long calls(String prefix) {
        return memory.calls.stream().filter(call -> call.startsWith(prefix)).count();
    }

    private GatherPlan onlyPlan() {
        assertThat(rig.gather.plans).hasSize(1);
        return rig.gather.plans.get(0);
    }

    // ================================================================ 成组

    @Test
    void 两个人一轮成组_票置matched并按人数收紧TTL_整组交给开局管线() {
        join(1001, Q1V1);
        join(1002, Q1V1);

        assertThat(round()).isEqualTo(MatcherRound.OK);

        GatherPlan plan = onlyPlan();
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_1V1);
        assertThat(plan.battleConfigId()).isZero();
        assertThat(plan.members()).containsExactly(1001L, 1002L);
        assertThat(plan.tickets()).containsExactly(Map.entry(1001L, "t-1001"), Map.entry(1002L, "t-1002"));
        assertThat(plan.onFail()).as("凑单入口：失败时幸存者回队首").isEqualTo(FailPolicy.REQUEUE_SURVIVORS);
        assertThat(plan.presetBattleId()).isZero();
        assertThat(plan.activityContext()).isNull();
        for (long playerId : new long[] {1001, 1002}) {
            assertThat(stateOf(playerId)).isEqualTo(TicketState.MATCHED);
            assertThat(memory.ttlMs(playerId)).as("2 人的 matched TTL 是 48 s").isEqualTo(48_000);
        }
        assertThat(memory.queueMembers(Q1V1)).isEmpty();
        assertThat(memory.rankOf(Q1V1)).isEmpty();
        assertThat(memory.lockHolder(Q1V1)).as("锁必须释放").isEmpty();
        assertThat(rig.depth(Q1V1)).as("深度是弹组之前读到的").isEqualTo(2.0);
        assertThat(rig.starved(Q1V1)).isEqualTo(0.0);
        assertThat(rig.waitSamples(Q1V1)).isEqualTo(1);
        assertThat(rig.meters.get("xm.match.group.rating.spread").tags("mode", "MATCH_MODE_1V1").summary().count()).isEqualTo(1);
    }

    @Test
    void 持锁期间连续凑多组() {
        for (long playerId = 1; playerId <= 5; playerId++) {
            join(playerId, Q1V1);
        }

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).extracting(GatherPlan::members).containsExactly(List.of(1L, 2L), List.of(3L, 4L));
        assertThat(memory.queueMembers(Q1V1)).containsExactly("5");
        assertThat(stateOf(5)).isEqualTo(TicketState.QUEUED);
        assertThat(rig.waitSamples(Q1V1)).isEqualTo(2);
        assertThat(calls("tryLockQueue(")).as("两组在同一次持锁里弹出").isEqualTo(1);
    }

    @Test
    void PVE组队按配置的人数凑_成员按等待序_五人的matchedTTL是66秒() {
        long[] ratings = {300_000, 100_000, 200_000, 150_000, 50_000};
        for (int i = 0; i < 5; i++) {
            join(11401 + i, PVE1, ratings[i]);
        }

        assertThat(round()).isEqualTo(MatcherRound.OK);

        GatherPlan plan = onlyPlan();
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThat(plan.battleConfigId()).isEqualTo(1);
        assertThat(plan.members()).as("不看评分，纯等待序").containsExactly(11401L, 11402L, 11403L, 11404L, 11405L);
        assertThat(memory.ttlMs(11403)).isEqualTo(66_000);
        assertThat(rig.meters.find("xm.match.group.rating.spread").tags("mode", "MATCH_MODE_PVE_TEAM").summary()).as("非评分模式不记评分极差").isNull();
    }

    @Test
    void 五对五的成员顺序_锚点在前其余按分差由近到远_原样传给开局管线() {
        // 锚点先入队并已等 60 s（容差 1000）；其余 9 人与锚点的分差各不相同
        join(100, Q5V5, 150_000);
        clock.advanceSeconds(60);
        long[] ratings = {159_000, 151_000, 142_000, 150_500, 170_000, 150_000, 165_000, 148_000, 153_000};
        for (int i = 0; i < ratings.length; i++) {
            join(101 + i, Q5V5, ratings[i]);
        }

        assertThat(round()).isEqualTo(MatcherRound.OK);

        GatherPlan plan = onlyPlan();
        // 分差：106→0、104→500、102→1000、108→2000、109→3000、103→8000、101→9000、107→15000、105→20000
        assertThat(plan.members()).containsExactly(100L, 106L, 104L, 102L, 108L, 109L, 103L, 101L, 107L, 105L);
        assertThat(new ArrayList<>(plan.tickets().keySet())).as("票号的遍历顺序同成员顺序").isEqualTo(plan.members());
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_5V5);
        assertThat(memory.ttlMs(105)).as("10 人的 matched TTL 是 96 s").isEqualTo(96_000);
        assertThat(memory.queueMembers(Q5V5)).isEmpty();
    }

    // ================================================================ 暂停

    @Test
    void battle池为空时暂停_不抢锁不碰队列_节点回来后继续() {
        rig.nodes.remove(1);
        join(4101, Q1V1);
        join(4102, Q1V1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.PAUSED_NO_BATTLE);

        assertThat(memory.calls).as("暂停的一轮连注册集都不读").isEmpty();
        assertThat(rig.gather.plans).isEmpty();
        assertThat(stateOf(4101)).isEqualTo(TicketState.QUEUED);
        assertThat(stateOf(4102)).isEqualTo(TicketState.QUEUED);
        assertThat(memory.queueMembers(Q1V1)).containsExactly("4101", "4102");
        assertThat(rig.meters.get("xm.match.battle.nodes").tags("state", "accepting").gauge().value()).isEqualTo(0.0);

        rig.nodes.add(FakeBattleNodes.node(2, "battle-inst-2", 21201));
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(onlyPlan().members()).containsExactly(4101L, 4102L);
        assertThat(rig.meters.get("xm.match.battle.nodes").tags("state", "accepting").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void battle目录读不到或只有关闸的节点_同样暂停() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();

        rig.nodes.readFailed = true;
        assertThat(round()).isEqualTo(MatcherRound.PAUSED_NO_BATTLE);
        rig.nodes.readFailed = false;
        rig.nodes.set(FakeBattleNodes.node(1, "battle-inst-1", 21200).toBuilder().setAccepting(false).build());
        assertThat(round()).isEqualTo(MatcherRound.PAUSED_NO_BATTLE);

        assertThat(memory.calls).isEmpty();
        assertThat(rig.gather.plans).isEmpty();
        assertThat(rig.meters.get("xm.match.battle.nodes").tags("state", "not_accepting").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void 发号租约无效时暂停_队列不动_续期恢复后继续() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();
        rig.leaseValid.set(false);

        assertThat(round()).isEqualTo(MatcherRound.PAUSED_NO_LEASE);
        rig.leaseLost.set(true);
        assertThat(round()).as("真正丢失也是同一种暂停").isEqualTo(MatcherRound.PAUSED_NO_LEASE);

        assertThat(memory.calls).isEmpty();
        assertThat(rig.gather.plans).isEmpty();
        assertThat(memory.queueMembers(Q1V1)).containsExactly("1", "2");

        rig.leaseLost.set(false);
        rig.leaseValid.set(true);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).hasSize(1);
    }

    @Test
    void 开局许可已满时暂停_不弹组() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();
        rig.gather.permits(0);

        assertThat(round()).isEqualTo(MatcherRound.PAUSED_SATURATED);

        assertThat(memory.calls).isEmpty();
        assertThat(rig.gather.plans).as("没有把人弹出来再让管线以过载打回").isEmpty();
        assertThat(stateOf(1)).isEqualTo(TicketState.QUEUED);
    }

    @Test
    void 多个暂停条件同时成立_按池子_租约_许可的先后报() {
        rig.gather.permits(0);
        rig.leaseValid.set(false);
        assertThat(round()).isEqualTo(MatcherRound.PAUSED_NO_LEASE);

        rig.nodes.remove(1);
        assertThat(round()).isEqualTo(MatcherRound.PAUSED_NO_BATTLE);
    }

    @Test
    void 暂停期间每10秒告警一次(CapturedOutput output) {
        rig.nodes.remove(1);

        round();
        round();
        rig.nanos.addAndGet(9_999_000_000L);
        round();
        assertThat(occurrences(output.getAll(), "凑单暂停：没有可分配的 battle 节点")).isEqualTo(1);

        rig.nanos.addAndGet(1_000_000L);
        round();
        assertThat(occurrences(output.getAll(), "凑单暂停：没有可分配的 battle 节点")).isEqualTo(2);
    }

    @Test
    void 一轮中途许可用完_这条队列不再弹组_后面的队列照常报深度() {
        QueueRef second = new QueueRef(3, 2);
        for (long playerId = 1; playerId <= 4; playerId++) {
            join(playerId, Q1V1);
        }
        join(9, second);
        store.afterPop = result -> rig.gather.permits(0);

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
        assertThat(memory.queueMembers(Q1V1)).as("第二组没有弹").containsExactly("3", "4");
        assertThat(calls("pop(")).isEqualTo(1);
        assertThat(rig.depth(second)).as("许可用完之后的队列仍抢锁、报深度").isEqualTo(1.0);
        assertThat(memory.lockHolder(Q1V1)).isEmpty();
        assertThat(memory.lockHolder(second)).isEmpty();
    }

    @Test
    void 一轮中途租约失效_不再弹组() {
        for (long playerId = 1; playerId <= 4; playerId++) {
            join(playerId, Q1V1);
        }
        store.afterPop = result -> rig.leaseValid.set(false);

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).hasSize(1);
        assertThat(memory.queueMembers(Q1V1)).containsExactly("3", "4");
    }

    // ================================================================ 锁、深度、剔除

    @Test
    void 抢不到锁_不弹组不放别人的锁_自己那份深度记0_接管后报实值() {
        join(7002, Q1V1);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.depth(Q1V1)).as("先作为持锁实例报过 1").isEqualTo(1.0);

        join(7003, Q1V1);
        assertThat(memory.tryLockQueue(Q1V1, "other-instance", 10_000, d())).isTrue();
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).as("拿不到锁不得弹组").isEmpty();
        assertThat(memory.lockHolder(Q1V1)).as("不得释放别的实例的锁").contains("other-instance");
        assertThat(rig.depth(Q1V1)).as("不是持锁实例：自己那份置 0，看板按实例求和才不翻倍").isEqualTo(0.0);
        assertThat(rig.starved(Q1V1)).isEqualTo(0.0);
        assertThat(memory.calls).as("没拿到锁就不读队列").containsExactly("queueIndex()", "tryLockQueue(3:0)");

        memory.unlockQueue(Q1V1, "other-instance", d());
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.depth(Q1V1)).isEqualTo(2.0);
        assertThat(onlyPlan().members()).containsExactly(7002L, 7003L);
    }

    @Test
    void 凑不满的队列_持锁实例照样报深度_不读快照_队列留在注册集() {
        join(7001, Q1V1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(memory.calls).containsExactly("queueIndex()", "tryLockQueue(3:0)", "queueLength(3:0)", "unlockQueue(3:0)");
        assertThat(rig.depth(Q1V1)).isEqualTo(1.0);
        assertThat(rig.starved(Q1V1)).as("人数不够谈不上锚点饥饿").isEqualTo(0.0);
        assertThat(memory.indexed(Q1V1)).isTrue();
        assertThat(memory.lockHolder(Q1V1)).isEmpty();
        assertThat(rig.gather.plans).isEmpty();
    }

    @Test
    void 空队列从注册集剔除_深度归零() {
        QueueRef empty = new QueueRef(1, 3);
        memory.putIndexMember(empty.queueKey());

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(memory.indexed(empty)).isFalse();
        assertThat(rig.depth(empty)).isEqualTo(0.0);
        assertThat(memory.lockHolder(empty)).isEmpty();
    }

    @Test
    void 副本号不在表里的队列共用一个标签_深度按标签求和而不是互相覆盖() {
        QueueRef a = new QueueRef(3, 777);
        QueueRef b = new QueueRef(3, 888);
        join(1, a);
        join(2, b);

        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.depth(a)).as("两条队列各 1 人，标签都是 config=other").isEqualTo(2.0);
        assertThat(rig.depth(b)).isEqualTo(2.0);

        // 其中一条被别的实例持锁：只剩自己持有的那条的深度
        assertThat(memory.tryLockQueue(b, "other-instance", 10_000, d())).isTrue();
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.depth(a)).isEqualTo(1.0);
    }

    @Test
    void 以前报过的队列被别的实例弹空剔除后_本实例的旧读数归零() {
        join(1, Q1V1, 150_000);
        join(2, Q1V1, 260_000);
        clock.advanceSeconds(60);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.depth(Q1V1)).isEqualTo(2.0);
        assertThat(rig.starved(Q1V1)).isEqualTo(60.0);

        // 别的实例接手并把队列弹空、从注册集剔除：本实例此后在注册集里再也见不到这条队列
        memory.cancel(1, "t-1", Q1V1, d());
        memory.cancel(2, "t-2", Q1V1, d());
        assertThat(memory.pruneIfEmpty(Q1V1, d())).isTrue();

        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.depth(Q1V1)).isEqualTo(0.0);
        assertThat(rig.starved(Q1V1)).isEqualTo(0.0);
    }

    // ================================================================ 饥饿与退避

    @Test
    void 分差超过曲线上限的两人_饥饿秒数可观测_等满90秒成组后归零(CapturedOutput output) {
        join(16001, Q1V1, 150_000);
        join(16002, Q1V1, 260_000);

        clock.advanceSeconds(60);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).isEmpty();
        assertThat(rig.starved(Q1V1)).isEqualTo(60.0);
        assertThat(memory.queueMembers(Q1V1)).as("队列不动").containsExactly("16001", "16002");
        assertThat(occurrences(output.getAll(), "仍凑不到候选，队列在饥饿")).as("曲线已到顶仍凑不到：告警").isEqualTo(1);

        clock.advanceSeconds(29);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).isEmpty();
        assertThat(rig.starved(Q1V1)).isEqualTo(89.0);
        assertThat(occurrences(output.getAll(), "仍凑不到候选，队列在饥饿")).as("同一条队列 10 s 内只告警一次（限频按单调时钟）").isEqualTo(1);

        clock.advanceSeconds(1);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(onlyPlan().members()).containsExactly(16001L, 16002L);
        assertThat(rig.meters.get("xm.match.wait").tags("mode", "MATCH_MODE_1V1").summary().totalAmount()).isEqualTo(90.0);
        assertThat(rig.meters.get("xm.match.group.rating.spread").tags("mode", "MATCH_MODE_1V1").summary().totalAmount()).as("极差按评分点记")
                .isEqualTo(1100.0);

        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.starved(Q1V1)).isEqualTo(0.0);
    }

    @Test
    void 退避未到点的票不参与凑单_到点后照常成组() {
        join(1, Q1V1);
        join(2, Q1V1);
        // 模拟一次无肇事者的 gather 失败：弹出后带 2 s 退避回队首
        List<TicketRef> refs = List.of(new TicketRef(1, "t-1"), new TicketRef(2, "t-2"));
        assertThat(memory.pop(Q1V1, "tok", refs, 48_000, d())).isInstanceOf(PopResult.Popped.class);
        assertThat(memory.requeueFront(Q1V1, refs, TTL, 2_000, d())).isEqualTo(2);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);
        clock.advanceMs(1_999);
        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).as("2 s 之内不再弹同一批人").isEmpty();
        assertThat(calls("pop(")).isZero();
        assertThat(calls("drop(")).as("只是退避没到点的人不会被剔除").isZero();
        assertThat(memory.queueMembers(Q1V1)).containsExactly("1", "2");
        assertThat(rig.depth(Q1V1)).isEqualTo(2.0);

        clock.advanceMs(1);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
    }

    @Test
    void 只在队列前256人里找_更深的成员等前面的人走了才进入视野() {
        // 队首 A；其后 255 个彼此相差 300 分、谁也配不上谁的人；第 257 位才是与 A 同分的 B
        join(1, Q1V1, 150_000);
        for (int i = 0; i < 255; i++) {
            join(1000 + i, Q1V1, 1_000_000 + i * 30_000L);
        }
        join(2, Q1V1, 150_000);

        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).as("B 在前缀之外").isEmpty();
        assertThat(rig.depth(Q1V1)).isEqualTo(257.0);

        // 前面走了一个人，B 进入前 256
        assertThat(memory.cancel(1100, "t-1100", Q1V1, d())).isTrue();
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
    }

    // ================================================================ 成员校验

    @Test
    void 重连租约中的成员本轮跳过_票据与队列位置都保留_回来后照常成组() {
        join(1, Q1V1);
        join(2, Q1V1);
        join(3, Q1V1);
        rig.players.disconnected(1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(onlyPlan().members()).as("掉线的队首不进组").containsExactly(2L, 3L);
        assertThat(stateOf(1)).isEqualTo(TicketState.QUEUED);
        assertThat(memory.queueMembers(Q1V1)).as("原位保留").containsExactly("1");
        assertThat(calls("drop(")).isZero();

        rig.players.online(1, 1, 7);
        join(4, Q1V1);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans.get(1).members()).containsExactly(1L, 4L);
    }

    @Test
    void 已登出或没有位置记录的成员删票出局_带校验时读到的票号() {
        join(1, Q1V1);
        join(2, Q1V1);
        join(3, Q1V1);
        join(4, Q1V1);
        rig.players.loggedOut(1);
        rig.players.location(2, LocationStatus.MISSING);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(onlyPlan().members()).containsExactly(3L, 4L);
        assertThat(memory.ticketOf(1)).as("登出的人票被删，可以重排").isEmpty();
        assertThat(memory.ticketOf(2)).isEmpty();
        assertThat(memory.queueMembers(Q1V1)).isEmpty();
        assertThat(memory.calls).contains("drop(3:0,1,OFFLINE)", "drop(3:0,2,OFFLINE)");
        assertThat(rig.count("xm.match.queue.dropped", "reason", "offline")).isEqualTo(2.0);
    }

    @Test
    void 排队期间进了别的战斗的成员删票出局_有锁就不再读位置() {
        join(1, Q1V1);
        join(2, Q1V1);
        join(3, Q1V1);
        rig.players.inBattle(1, true);
        rig.players.reads.clear();
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(onlyPlan().members()).containsExactly(2L, 3L);
        assertThat(memory.ticketOf(1)).isEmpty();
        assertThat(memory.calls).contains("drop(3:0,1,IN_BATTLE)");
        assertThat(rig.count("xm.match.queue.dropped", "reason", "in_battle")).isEqualTo(1.0);
        assertThat(rig.players.reads).as("先查战斗锁、再读位置；有锁的人不读位置")
                .containsExactly("lock:1", "lock:2", "location:2", "lock:3", "location:3");
    }

    @Test
    void 期间重新排队的同一玩家不被离线判定误伤() {
        // 校验时读到的是旧票 t-1；删票带的是旧票号，玩家此刻的新票不受影响
        join(1, Q1V1);
        join(2, Q1V1);
        rig.players.loggedOut(1);
        HookedPlayers hooked = new HookedPlayers(rig.players);
        hooked.beforeLocation(1, () -> {
            memory.cancel(1, "t-1", Q1V1, d());
            memory.enqueue(1, "t2-1", Q1V1, 1, 150_000, TTL, d());
        });
        QueueMatcher hookedMatcher = rig.matcher(store, hooked, MatcherRig.defaults());

        assertThat(hookedMatcher.runRound(() -> false)).isEqualTo(MatcherRound.OK);

        assertThat(memory.ticketOf(1)).map(Ticket::ticketId).as("新票还在").contains("t2-1");
        assertThat(memory.queueMembers(Q1V1)).contains("1");
        assertThat(rig.count("xm.match.queue.dropped", "reason", "offline")).as("存储没有摘掉他，不计数").isEqualTo(0.0);
    }

    @Test
    void 读战斗锁失败_结束这条队列的本轮_不做出局判定_锁照常释放() {
        join(1, Q1V1);
        join(2, Q1V1);
        rig.players.failLock(1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.ERROR);

        assertThat(rig.gather.plans).isEmpty();
        assertThat(calls("drop(")).isZero();
        assertThat(calls("pop(")).isZero();
        assertThat(memory.queueMembers(Q1V1)).containsExactly("1", "2");
        assertThat(stateOf(1)).isEqualTo(TicketState.QUEUED);
        assertThat(memory.lockHolder(Q1V1)).isEmpty();

        rig.players.heal(1);
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
    }

    @Test
    void 读候选的位置失败_同样只结束本轮() {
        join(1, Q1V1);
        join(2, Q1V1);
        rig.players.failLocation(2);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.ERROR);

        assertThat(rig.gather.plans).isEmpty();
        assertThat(calls("drop(")).isZero();
        assertThat(memory.ticketOf(2)).as("读不到不等于离线").isPresent();
        assertThat(memory.lockHolder(Q1V1)).isEmpty();
    }

    @Test
    void 队列里没有票的残项被摘掉_票据属于别的队列的残项只摘这条队列的那一项() {
        QueueRef other = new QueueRef(3, 9);
        memory.putQueueMember(Q1V1, "999", 150_000L);
        rig.players.online(5, 1, 7);
        memory.enqueue(5, "t-5", other, 1, 150_000, TTL, d());
        memory.putQueueMember(Q1V1, "5", 150_000L);
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).extracting(GatherPlan::members).as("残项不进组").containsExactly(List.of(1L, 2L));
        assertThat(memory.calls).contains("drop(3:0,999,INVALID)", "drop(3:0,5,INVALID)");
        assertThat(memory.queueMembers(Q1V1)).isEmpty();
        assertThat(memory.ticketOf(5)).map(Ticket::state).as("他在自己队列里的票不动").contains(TicketState.QUEUED);
        assertThat(memory.queueMembers(other)).containsExactly("5");
        assertThat(rig.count("xm.match.queue.dropped", "reason", "invalid")).isEqualTo(2.0);
    }

    @Test
    void 非法成员串被摘掉() {
        memory.putQueueMember(Q1V1, "abc", null);
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(memory.calls).contains("dropMalformed(3:0,abc)");
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
        assertThat(memory.queueMembers(Q1V1)).isEmpty();
        assertThat(rig.count("xm.match.queue.dropped", "reason", "invalid")).isEqualTo(1.0);
    }

    @Test
    void 评分镜像缺分的成员按票里的评分用_计一次异常_不回写() {
        join(1, Q1V1, 150_000);
        rig.players.online(2, 1, 7);
        memory.putTicket(2, new Ticket("t-2", 3, 0, TicketState.QUEUED, clock.peekMs(), 1, Q1V1.queueKey(), 153_000, 0, 0, 0), TTL);
        memory.putQueueMember(Q1V1, "2", null);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
        assertThat(rig.count("xm.match.queue.anomalies", "reason", "missing_score")).isEqualTo(1.0);
        assertThat(memory.calls).as("只有读、弹组与锁，没有任何补写").allMatch(call -> call.startsWith("queueIndex") || call.startsWith("tryLockQueue")
                || call.startsWith("queueLength") || call.startsWith("snapshot") || call.startsWith("readAll") || call.startsWith("pop")
                || call.startsWith("unlockQueue"));
    }

    // ================================================================ 弹组被拒、结局不明

    @Test
    void 校验之后弹组之前有人取消_弹组被拒_其余人原位不动_本轮重挑() {
        join(8101, Q1V1);
        join(8102, Q1V1);
        store.beforePop = members -> {
            assertThat(members).extracting(TicketRef::playerId).as("同评分按等待先后").containsExactly(8101L, 8102L);
            assertThat(memory.cancel(8101, "t-8101", Q1V1, d())).as("弹组之前票还是 queued，取消必须成功").isTrue();
            store.beforePop = null;
        };
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).as("已取消的成员不得进开局").isEmpty();
        assertThat(memory.ticketOf(8101)).as("A 的票保持已删，可以重排").isEmpty();
        assertThat(stateOf(8102)).as("B 根本没离开队列").isEqualTo(TicketState.QUEUED);
        assertThat(memory.ttlMs(8102)).as("B 的票没有被收紧成 matched TTL").isEqualTo(TTL);
        assertThat(memory.queueMembers(Q1V1)).containsExactly("8102");
        assertThat(memory.calls).contains("drop(3:0,8101,INVALID)");
        assertThat(calls("pop(")).as("重挑时只剩 B 一个人，挑不出组").isEqualTo(1);
        assertThat(calls("snapshot(")).as("被拒之后在本轮内重取了一次快照").isEqualTo(2);
        assertThat(memory.lockHolder(Q1V1)).isEmpty();

        // A 重排后与 B 正常成组（B 在队首，是锚点）
        memory.enqueue(8101, "t2-8101", Q1V1, 1, 150_000, TTL, d());
        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(onlyPlan().members()).containsExactly(8102L, 8101L);
        assertThat(onlyPlan().tickets()).containsEntry(8101L, "t2-8101");
    }

    @Test
    void 弹组被拒的人已经换了新票_不摘他_本轮重挑时带上新票() {
        join(1, Q1V1);
        join(2, Q1V1);
        store.beforePop = members -> {
            memory.cancel(1, "t-1", Q1V1, d());
            memory.enqueue(1, "t2-1", Q1V1, 1, 150_000, TTL, d());
            store.beforePop = null;
        };

        assertThat(round()).isEqualTo(MatcherRound.OK);

        GatherPlan plan = onlyPlan();
        assertThat(plan.members()).as("重排的人到了队尾，B 成了锚点").containsExactly(2L, 1L);
        assertThat(plan.tickets()).containsExactly(Map.entry(2L, "t-2"), Map.entry(1L, "t2-1"));
        assertThat(rig.count("xm.match.queue.dropped", "reason", "invalid")).as("他此刻是一张有效的 queued 票，存储没有摘").isEqualTo(0.0);
    }

    @Test
    void 弹组一再被拒_同一条队列一轮内至多重挑3次_用完计一次异常() {
        join(8201, Q1V1);
        join(8202, Q1V1);
        AtomicInteger pops = new AtomicInteger();
        store.popOverride = members -> {
            pops.incrementAndGet();
            return new PopResult.Invalid(List.of(8202L));
        };
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(pops.get()).as("第一次 + 3 次重挑").isEqualTo(1 + QueueMatcher.MAX_REPICKS);
        assertThat(rig.count("xm.match.queue.anomalies", "reason", "repick_exhausted")).isEqualTo(1.0);
        assertThat(calls("drop(3:0,8202,INVALID)")).isEqualTo(4);
        assertThat(rig.gather.plans).isEmpty();
        assertThat(memory.queueMembers(Q1V1)).containsExactly("8201", "8202");
        assertThat(stateOf(8201)).isEqualTo(TicketState.QUEUED);
        assertThat(memory.lockHolder(Q1V1)).isEmpty();
    }

    @Test
    void 弹组的应答丢了_用同一个标记重发一次_存储认出重放_照常开局() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.faults.failNext("pop:after");
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(calls("pop(")).isEqualTo(2);
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
        assertThat(stateOf(1)).isEqualTo(TicketState.MATCHED);
        assertThat(calls("drop(")).as("重发用的是同一个标记：没有被当成「这些人已不在队列」而去摘人").isZero();
    }

    @Test
    void 弹组两次都没执行成_本轮结束_队列原样_下一轮重来() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.faults.failNext("pop").failNext("pop");

        assertThat(round()).isEqualTo(MatcherRound.ERROR);

        assertThat(rig.gather.plans).isEmpty();
        assertThat(memory.queueMembers(Q1V1)).containsExactly("1", "2");
        assertThat(stateOf(1)).isEqualTo(TicketState.QUEUED);
        assertThat(memory.lockHolder(Q1V1)).isEmpty();

        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).hasSize(1);
    }

    @Test
    void 弹组已生效但两次应答都丢了_不开局_票据按matchedTTL自愈() {
        join(1, Q1V1);
        join(2, Q1V1);
        AtomicInteger attempts = new AtomicInteger();
        store.beforePop = members -> memory.faults.failNext(attempts.incrementAndGet() == 1 ? "pop:after" : "pop");

        assertThat(round()).isEqualTo(MatcherRound.ERROR);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(rig.gather.plans).as("不知道弹没弹成，不能开局").isEmpty();
        assertThat(stateOf(1)).isEqualTo(TicketState.MATCHED);
        assertThat(memory.ttlMs(2)).isEqualTo(48_000);
        assertThat(memory.queueMembers(Q1V1)).isEmpty();
        assertThat(memory.lockHolder(Q1V1)).isEmpty();

        clock.advanceSeconds(48);
        assertThat(memory.ticketOf(1)).as("TTL 到期后玩家可以重排").isEmpty();
        assertThat(memory.ticketOf(2)).isEmpty();
    }

    // ================================================================ 依赖故障与边角

    @Test
    void 读注册集失败_本轮报错_下一轮照常() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.faults.failNext("queueIndex");

        assertThat(round()).isEqualTo(MatcherRound.ERROR);
        assertThat(rig.gather.plans).isEmpty();

        assertThat(round()).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).hasSize(1);
    }

    @Test
    void 一条队列抢锁失败_不影响别的队列_没拿到的锁不去放() {
        QueueRef second = new QueueRef(3, 2);
        join(1, Q1V1);
        join(2, Q1V1);
        join(3, second);
        join(4, second);
        memory.faults.failNext("tryLockQueue");
        memory.calls.clear();

        assertThat(round()).as("有队列因依赖故障没处理").isEqualTo(MatcherRound.ERROR);

        assertThat(memory.calls).doesNotContain("unlockQueue(3:0)");
        assertThat(onlyPlan().members()).as("第二条队列照常凑单").containsExactly(3L, 4L);
        assertThat(memory.queueMembers(Q1V1)).containsExactly("1", "2");
    }

    @Test
    void 一条队列冒出意料之外的异常_只结束它自己的本轮_锁照常释放_后面的队列照常凑单() {
        QueueRef second = new QueueRef(3, 2);
        join(1, Q1V1);
        join(2, Q1V1);
        join(3, second);
        join(4, second);
        // 只让第一条队列的弹组炸一次（不是依赖异常）
        store.popOverride = members -> {
            store.popOverride = null;
            throw new IllegalStateException("这条队列的数据触发了一个 bug");
        };

        assertThat(round()).isEqualTo(MatcherRound.ERROR);

        assertThat(memory.lockHolder(Q1V1)).isEmpty();
        assertThat(memory.queueMembers(Q1V1)).containsExactly("1", "2");
        assertThat(onlyPlan().members()).as("第二条队列没有被拖累").containsExactly(3L, 4L);
        assertThat(rig.depth(Q1V1)).as("出事之前读到的深度照常上报").isEqualTo(2.0);
    }

    @Test
    void 读队列长度失败_锁照常释放() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.faults.failNext("queueLength");

        assertThat(round()).isEqualTo(MatcherRound.ERROR);

        assertThat(memory.lockHolder(Q1V1)).isEmpty();
        assertThat(rig.gather.plans).isEmpty();
    }

    @Test
    void 放锁失败只记日志_这一轮照常算成功_锁由TTL兜底() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.faults.failNext("unlockQueue");

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(rig.gather.plans).hasSize(1);
        assertThat(memory.lockHolder(Q1V1)).contains(MatcherRig.INSTANCE);
        clock.advanceSeconds(10);
        assertThat(memory.lockHolder(Q1V1)).as("凑单锁的 TTL 是 10 s").isEmpty();
    }

    @Test
    void 注册集里的坏键与没有人数配置的队列_告警跳过_不动数据_不影响正常队列() {
        QueueRef unconfiguredPve = new QueueRef(5, 2);
        QueueRef soloQueue = new QueueRef(4, 1);
        QueueRef unknownMode = new QueueRef(99, 0);
        memory.putIndexMember("garbage");
        for (QueueRef odd : List.of(unconfiguredPve, soloQueue, unknownMode)) {
            memory.putQueueMember(odd, "77", 150_000L);
        }
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();

        assertThat(round()).isEqualTo(MatcherRound.OK);

        assertThat(memory.calls).noneMatch(call -> call.contains("5:2") || call.contains("4:1") || call.contains("99:0"));
        assertThat(memory.queueMembers(unconfiguredPve)).containsExactly("77");
        assertThat(memory.indexed(unconfiguredPve)).isTrue();
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
    }

    @Test
    void 停机信号_队列之间与两次弹组之间收手() {
        for (long playerId = 1; playerId <= 4; playerId++) {
            join(playerId, Q1V1);
        }
        memory.calls.clear();

        assertThat(matcher.runRound(() -> true)).isEqualTo(MatcherRound.OK);
        assertThat(calls("tryLockQueue(")).as("已经要停了：一条队列都不碰").isZero();
        assertThat(rig.depth(Q1V1)).as("没看全注册集的一轮不动 gauge").isNull();

        AtomicBoolean stop = new AtomicBoolean();
        store.afterPop = result -> stop.set(true);
        assertThat(matcher.runRound(stop::get)).isEqualTo(MatcherRound.OK);
        assertThat(rig.gather.plans).as("弹完手上这一组就停，已弹出的照常交给开局管线").hasSize(1);
        assertThat(memory.queueMembers(Q1V1)).containsExactly("3", "4");
        assertThat(memory.lockHolder(Q1V1)).isEmpty();
    }

    @Test
    void 直接对一条队列凑单_不依赖注册集_成功与依赖故障分别回真假() {
        join(1, Q1V1);
        join(2, Q1V1);
        memory.calls.clear();

        assertThat(matcher.matchQueueOnce(Q1V1)).isTrue();

        assertThat(memory.calls).doesNotContain("queueIndex()");
        assertThat(onlyPlan().members()).containsExactly(1L, 2L);
        assertThat(rig.depth(Q1V1)).isEqualTo(2.0);

        join(3, Q1V1);
        join(4, Q1V1);
        memory.faults.failNext("snapshot");
        assertThat(matcher.matchQueueOnce(Q1V1)).isFalse();
        assertThat(memory.lockHolder(Q1V1)).isEmpty();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
