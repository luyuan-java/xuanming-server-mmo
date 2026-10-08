package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.gather.GatherPlan;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * 从基线移植的凑单端到端用例（match-spec §15.3 列给凑单的那些 miniredis 用例）：票据存储走 {@link TicketStore} 接口，由子类决定是内存实现
 * （缺省执行，{@link MatcherPortedInMemoryTest}）还是真 Redis 实现（{@code -Dxm.it.redis}，{@link MatcherRedisIntegrationTest}）——
 * 同一套断言跑两遍，凑单在真脚本上的行为与在替身上的行为不许分叉。
 *
 * <table>
 *   <caption>与基线用例的对应</caption>
 *   <tr><td>{@code rating_match_test.go}</td><td>TestNewPlayersMatchImmediately、TestRatingGapWaitsUntilToleranceWidens、TestClosestCandidateWins、
 *       TestHeadAnchorDoesNotBlockLaterAnchors、TestPveTeamIgnoresRating、TestQueueMirrorStaysConsistentAcrossOperations</td></tr>
 *   <tr><td>{@code rating_review_fix_test.go}</td><td>TestStarvedAnchorsMatchAfterMaxWait</td></tr>
 *   <tr><td>{@code review_fix_test.go}</td><td>TestMatcherDropsMemberCancelledAfterPop、TestPopGroupDedupesDuplicateEntries</td></tr>
 *   <tr><td>{@code crosszone_test.go}</td><td>TestMatcherMixesZonesAndShortensMatchedTTL、TestMatcherPrunesEmptyQueueFromIndex</td></tr>
 *   <tr><td>{@code ticket_cas_test.go}</td><td>TestQueueDepthReportedByLockHolderWhenShort、TestQueueDepthNotReportedWithoutLock、
 *       TestQueueDepthZeroedWhenQueuePruned</td></tr>
 * </table>
 *
 * <p>与基线写法的三点不同：（1）排队直接调 {@link TicketStore#enqueue}（排队入口 157 归别的包，评分作为入参给）；（2）凑单直接驱动
 * {@link QueueMatcher#matchQueueOnce}，不遍历全局注册集——真 Redis 上多人共用一个库，每个用例只碰自己随机副本号的队列与随机玩家号；
 * （3）「已等多久」靠改写票据的入队时刻模拟（真 Redis 的 {@code TIME} 拨不动），等待秒数都取在容差档位的中段，机器忙的时候多走几秒也不改变结论。
 * 玩家状态、开局管线、battle 目录仍是替身。
 */
abstract class MatcherPortedScenarios {

    protected static final int MODE_5V5 = 1;
    protected static final int MODE_1V1 = 3;
    protected static final int MODE_PVE_TEAM = 5;
    protected static final long TTL = MatcherRig.TICKET_TTL_MS;

    /** 每条队列的副本号各不相同：config 标签直接取副本号，gauge 互不干扰。 */
    protected final MatcherRig rig = new MatcherRig(id -> true);
    /** 本用例用到的队列与玩家：真 Redis 的子类据此只删自己写的键。 */
    protected final List<QueueRef> usedQueues = new ArrayList<>();
    protected final List<Long> usedPlayers = new ArrayList<>();

    // ================================================================ 子类提供：存储，以及绕过接口的摆数据 / 看数据

    protected abstract TicketStore store();

    /** 把票据的入队时刻改成「{@code ago} 之前」（相对存储自己的时间）。 */
    protected abstract void setEnqueuedAgo(long playerId, Duration ago);

    /** 队列全部成员，队首在前。 */
    protected abstract List<String> queueMembers(QueueRef queue);

    /** 评分镜像的全部成员与分数（centi）。 */
    protected abstract Map<String, Long> rankScores(QueueRef queue);

    protected abstract boolean indexed(QueueRef queue);

    protected abstract Optional<String> lockHolder(QueueRef queue);

    /** 票据剩余的 TTL（毫秒）；没有票为负数。 */
    protected abstract long ticketTtlMs(long playerId);

    /** 只把队列键登记进注册集（摆出「注册集里有、队列是空的」）。 */
    protected abstract void registerQueue(QueueRef queue);

    /** 直接往队尾放一项并写镜像、登记注册集（摆出同一玩家的第二份）。 */
    protected abstract void pushRaw(QueueRef queue, String member, long ratingCenti);

    /**
     * 只往评分镜像里放一个成员（摆出「镜像里有、队列里没有」的孤儿）。
     *
     * @return false = 这种存储摆不出来（跳过那一步）
     */
    protected abstract boolean addRankOnly(QueueRef queue, String member, long ratingCenti);

    // ================================================================ 小工具

    protected static Deadline d() {
        return MatcherRig.d();
    }

    /** 一条只属于本用例的队列（随机的正副本号）。 */
    protected QueueRef newQueue(int mode) {
        QueueRef queue = new QueueRef(mode, ThreadLocalRandom.current().nextInt(1_000_000, Integer.MAX_VALUE));
        usedQueues.add(queue);
        return queue;
    }

    /** {@code n} 个只属于本用例的玩家号（连续）。 */
    protected long[] newPlayers(int n) {
        long base = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 52);
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = base + i;
            usedPlayers.add(ids[i]);
        }
        return ids;
    }

    /** 上线并排进队列；返回票号。 */
    protected String join(long playerId, QueueRef queue, long ratingCenti, int zoneId) {
        rig.players.online(playerId, zoneId, 7);
        String ticketId = UUID.randomUUID().toString();
        assertThat(store().enqueue(playerId, ticketId, queue, zoneId, ratingCenti, TTL, d())).isInstanceOf(TicketStore.JoinResult.Created.class);
        return ticketId;
    }

    protected String join(long playerId, QueueRef queue, long ratingCenti) {
        return join(playerId, queue, ratingCenti, 1);
    }

    protected QueueMatcher matcher() {
        return rig.matcher(store(), MatcherRig.defaults());
    }

    protected static String str(long playerId) {
        return Long.toUnsignedString(playerId);
    }

    protected Ticket ticket(long playerId) {
        return store().read(playerId, d()).orElseThrow(() -> new AssertionError("没有票: " + str(playerId)));
    }

    /** 队列与评分镜像一致：成员数相等、成员集合相同（两者都不存在也算一致）。 */
    protected void assertMirrorConsistent(QueueRef queue) {
        List<String> list = queueMembers(queue);
        Map<String, Long> rank = rankScores(queue);
        assertThat(rank).as("镜像的成员数必须等于队列长度 list=%s", list).hasSize(list.size());
        assertThat(rank.keySet()).as("队列与镜像的成员集合必须相同").containsExactlyInAnyOrderElementsOf(list);
    }

    private List<GatherPlan> plans() {
        return rig.gather.plans;
    }

    // ================================================================ rating_match_test.go

    @Test
    void 两个新号第一轮就互配_评分落进票据与镜像_弹空后镜像一致() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        join(p[0], queue, 150_000, 1);
        join(p[1], queue, 150_000, 2);
        assertThat(ticket(p[0]).ratingCenti()).as("评分必须落进票据").isEqualTo(150_000);
        assertThat(rankScores(queue)).containsEntry(str(p[1]), 150_000L);
        assertMirrorConsistent(queue);

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[1]);
        assertThat(plans().get(0).tickets()).hasSize(2);
        assertThat(queueMembers(queue)).isEmpty();
        assertMirrorConsistent(queue);
    }

    @Test
    void 分差超过起始容差时不配_队列不动_锚点等到容差放宽后配上() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        join(p[0], queue, 150_000);
        join(p[1], queue, 180_000);
        assertThat(rankScores(queue)).as("镜像分必须是入队时给的评分").containsEntry(str(p[1]), 180_000L);
        QueueMatcher matcher = matcher();

        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).as("分差 300 > 起始容差 100").isEmpty();
        assertThat(ticket(p[0]).state()).as("候选不足时票据仍是 queued").isEqualTo(TicketState.QUEUED);
        assertThat(ticket(p[1]).state()).isEqualTo(TicketState.QUEUED);
        assertThat(queueMembers(queue)).as("队列不动").containsExactly(str(p[0]), str(p[1]));
        assertMirrorConsistent(queue);

        // 锚点已等 6 s：容差 200 仍不够
        setEnqueuedAgo(p[0], Duration.ofSeconds(6));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).isEmpty();

        // 10 s：容差 300，配上
        setEnqueuedAgo(p[0], Duration.ofSeconds(10));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[1]);
        assertMirrorConsistent(queue);
    }

    @Test
    void 容差内有多个候选时就近取_没被选中的留在队列() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(3);
        join(p[0], queue, 150_000);
        join(p[1], queue, 170_000);
        join(p[2], queue, 152_000);
        // 容差 1000，两人都在窗口内
        setEnqueuedAgo(p[0], Duration.ofSeconds(60));

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[2]);
        assertThat(queueMembers(queue)).as("分差更大的那个留下").containsExactly(str(p[1]));
        assertMirrorConsistent(queue);
    }

    @Test
    void 队首凑不到候选不阻塞后面的人() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(3);
        join(p[0], queue, 150_000);
        join(p[1], queue, 180_000);
        join(p[2], queue, 182_000);

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).as("第二位作锚点与第三位成组").containsExactly(p[1], p[2]);
        assertThat(queueMembers(queue)).containsExactly(str(p[0]));
        assertThat(ticket(p[0]).state()).isEqualTo(TicketState.QUEUED);
        assertMirrorConsistent(queue);
    }

    @Test
    void PVE组队不看评分_分差再大也按等待序立即成组_镜像照样维护() {
        QueueRef queue = newQueue(MODE_PVE_TEAM);
        long[] p = newPlayers(3);
        MatchProperties props = MatcherRig.props(Map.of(queue.configId(), 3), null);
        join(p[0], queue, 300_000);
        join(p[1], queue, 100_000);
        join(p[2], queue, 200_000);
        assertThat(rankScores(queue)).containsEntry(str(p[0]), 300_000L);
        assertMirrorConsistent(queue);

        assertThat(rig.matcher(store(), props).matchQueueOnce(queue)).isTrue();

        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[1], p[2]);
        assertThat(ticketTtlMs(p[0])).as("3 人的 matched TTL 是 54 s").isBetween(44_000L, 54_000L);
        assertMirrorConsistent(queue);
    }

    @Test
    void 队列与评分镜像在取消_弹组_回队首_剔除无效成员_重复项_孤儿镜像之后始终一致() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(4);
        String t0 = join(p[0], queue, 200_000);
        String t1 = join(p[1], queue, 200_000);
        join(p[2], queue, 200_000);
        assertMirrorConsistent(queue);
        QueueMatcher matcher = matcher();

        // 取消：队列与镜像一起摘
        assertThat(store().cancel(p[1], t1, queue, d())).isTrue();
        assertThat(queueMembers(queue)).containsExactly(str(p[0]), str(p[2]));
        assertMirrorConsistent(queue);

        // 弹组：两边一起摘空
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[2]);
        assertThat(queueMembers(queue)).isEmpty();
        assertMirrorConsistent(queue);

        // 回队首：镜像写回票里的评分
        assertThat(store().requeueFront(queue, "rq-" + UUID.randomUUID(), plans().get(0).ticketRefs(), TTL, 0, d())).isEqualTo(2);
        assertThat(queueMembers(queue)).containsExactly(str(p[0]), str(p[2]));
        assertThat(rankScores(queue)).as("回队首写回原评分").containsEntry(str(p[0]), 200_000L);
        assertMirrorConsistent(queue);

        // 票被删掉的成员：凑单校验时从队列与镜像一起摘掉
        assertThat(store().delete(new TicketRef(p[0], t0), d())).isTrue();
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(queueMembers(queue)).containsExactly(str(p[2]));
        assertThat(plans()).as("只剩一个人，没有新的一组").hasSize(1);
        assertMirrorConsistent(queue);

        // 重复项：同一玩家两份，弹组把两份一起摘掉
        pushRaw(queue, str(p[2]), 200_000);
        join(p[3], queue, 200_000);
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).hasSize(2);
        assertThat(plans().get(1).members()).containsExactly(p[2], p[3]);
        assertThat(queueMembers(queue)).as("重复的一份也被摘掉").isEmpty();
        assertMirrorConsistent(queue);

        // 孤儿镜像（队列空、镜像里有人）随空队列的剔除一并清掉
        registerQueue(queue);
        boolean orphanPlanted = addRankOnly(queue, "99999", 150_000);
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(indexed(queue)).isFalse();
        if (orphanPlanted) {
            assertThat(rankScores(queue)).as("孤儿镜像必须随空队列的剔除一起清掉").isEmpty();
        }
        assertMirrorConsistent(queue);
    }

    // ================================================================ rating_review_fix_test.go

    @Test
    void 分差超过曲线上限的两人_饥饿秒数可观测_锚点等满90秒后成组() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        join(p[0], queue, 150_000);
        join(p[1], queue, 260_000);
        QueueMatcher matcher = matcher();

        // 60 s：两人容差都到了上限 1000，分差 1100 仍不配
        setEnqueuedAgo(p[0], Duration.ofSeconds(60));
        setEnqueuedAgo(p[1], Duration.ofSeconds(60));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).isEmpty();
        assertThat(rig.starved(queue)).as("饥饿 gauge = 最久的等待").isBetween(60.0, 70.0);
        assertThat(queueMembers(queue)).as("队列不动").containsExactly(str(p[0]), str(p[1]));

        // 75 s：仍未到兜底
        setEnqueuedAgo(p[0], Duration.ofSeconds(75));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).isEmpty();
        assertThat(rig.starved(queue)).isBetween(75.0, 85.0);

        // 90 s：锚点容差无穷大，成组；队列弹空后饥饿归零
        setEnqueuedAgo(p[0], Duration.ofSeconds(90));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[1]);
        assertMirrorConsistent(queue);
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(rig.starved(queue)).isEqualTo(0.0);
    }

    @Test
    void 曲线上限调小_兜底秒数调短_分差超过上限的两人只等到兜底秒数() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        MatchProperties props = MatcherRig.props(null, new MatchProperties.Tolerance(null, null, null, 300, 20));
        QueueMatcher matcher = rig.matcher(store(), props);
        join(p[0], queue, 150_000);
        join(p[1], queue, 185_000);

        // 12 s：曲线 10 s 就到顶 300，分差 350 不配
        setEnqueuedAgo(p[0], Duration.ofSeconds(12));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).isEmpty();

        setEnqueuedAgo(p[0], Duration.ofSeconds(20));
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[1]);
        assertMirrorConsistent(queue);
    }

    // ================================================================ review_fix_test.go

    @Test
    void 校验之后弹组之前取消的成员不进开局_另一人原位保留长TTL_重排后正常成组() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        String ticketA = join(p[0], queue, 150_000, 1);
        String ticketB = join(p[1], queue, 150_000, 2);
        HookedTicketStore hooked = new HookedTicketStore(store());
        hooked.beforePop = members -> {
            assertThat(members).extracting(TicketRef::playerId).as("同评分按等待先后").containsExactly(p[0], p[1]);
            assertThat(store().cancel(p[0], ticketA, queue, d())).as("弹组之前票仍是 queued，取消必须成功").isTrue();
            hooked.beforePop = null;
        };
        QueueMatcher matcher = rig.matcher(hooked, MatcherRig.defaults());

        assertThat(matcher.matchQueueOnce(queue)).isTrue();

        assertThat(plans()).as("已取消的成员不得进开局").isEmpty();
        assertThat(store().read(p[0], d())).as("A 的票保持已删，玩家可以重排").isEmpty();
        Ticket b = ticket(p[1]);
        assertThat(b.ticketId()).isEqualTo(ticketB);
        assertThat(b.state()).as("B 仍是 queued").isEqualTo(TicketState.QUEUED);
        assertThat(ticketTtlMs(p[1])).as("B 保持长 TTL，没有被收紧成 matched 的 48 s").isGreaterThan(96_000L);
        assertThat(queueMembers(queue)).as("B 在队列里，A 不在").containsExactly(str(p[1]));
        assertThat(lockHolder(queue)).as("锁必须释放").isEmpty();
        assertMirrorConsistent(queue);

        // A 重排后与 B 正常成组（B 在队首，是锚点）
        String ticketA2 = join(p[0], queue, 150_000, 1);
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[1], p[0]);
        assertThat(plans().get(0).tickets()).containsEntry(p[0], ticketA2).containsEntry(p[1], ticketB);
    }

    @Test
    void 同一玩家在队列里有两份_弹组只算一个人_多余的一份一起摘掉() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        join(p[0], queue, 150_000);
        pushRaw(queue, str(p[0]), 150_000);
        join(p[1], queue, 150_000);
        assertThat(queueMembers(queue)).containsExactly(str(p[0]), str(p[0]), str(p[1]));

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).as("同评分按等待先后").containsExactly(p[0], p[1]);
        assertThat(plans().get(0).tickets()).hasSize(2);
        assertThat(queueMembers(queue)).as("多余的一份被丢弃，队列弹空").isEmpty();
    }

    // ================================================================ crosszone_test.go

    @Test
    void 不同区的两个人凑成一组_票据置matched并收紧TTL_队列弹空_锁释放() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        join(p[0], queue, 150_000, 1);
        join(p[1], queue, 150_000, 2);

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(plans()).as("匹配池不分 zone").hasSize(1);
        assertThat(plans().get(0).members()).containsExactly(p[0], p[1]);
        assertThat(plans().get(0).tickets()).as("弹组必须把各成员的票号带进开局").hasSize(2);
        for (long playerId : p) {
            assertThat(ticket(playerId).state()).isEqualTo(TicketState.MATCHED);
            assertThat(ticketTtlMs(playerId)).as("2 人的 matched TTL 是 48 s：覆盖最坏的串行 gather，又远短于排队的 6 h").isBetween(38_000L, 48_000L);
        }
        assertThat(ticket(p[0]).zoneId()).isEqualTo(1);
        assertThat(ticket(p[1]).zoneId()).as("两张票记录了各自的 zone").isEqualTo(2);
        assertThat(queueMembers(queue)).isEmpty();
        assertThat(lockHolder(queue)).isEmpty();
    }

    @Test
    void 空队列从注册集剔除_有人但凑不满的队列留在注册集() {
        QueueRef empty = newQueue(MODE_1V1);
        QueueRef live = newQueue(MODE_1V1);
        long[] p = newPlayers(1);
        registerQueue(empty);
        join(p[0], live, 150_000);
        QueueMatcher matcher = matcher();

        assertThat(matcher.matchQueueOnce(empty)).isTrue();
        assertThat(matcher.matchQueueOnce(live)).isTrue();

        assertThat(indexed(empty)).as("空队列必须被剔除").isFalse();
        assertThat(indexed(live)).as("有人的队列（凑不满）必须留在注册集").isTrue();
        assertThat(queueMembers(live)).containsExactly(str(p[0]));
    }

    // ================================================================ ticket_cas_test.go（D5c：深度由持锁实例上报）

    @Test
    void 凑不满的队列_持锁实例照样上报深度_锁释放_队列留在注册集() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(1);
        join(p[0], queue, 150_000);

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(rig.depth(queue)).isEqualTo(1.0);
        assertThat(lockHolder(queue)).as("锁必须释放").isEmpty();
        assertThat(indexed(queue)).isTrue();
    }

    @Test
    void 锁被别的实例持有_不弹组_不放别人的锁_自己那份深度记0_对方释放后接管并报实值() {
        QueueRef queue = newQueue(MODE_1V1);
        long[] p = newPlayers(2);
        join(p[0], queue, 150_000, 1);
        join(p[1], queue, 150_000, 2);
        assertThat(store().tryLockQueue(queue, "other-instance", 10_000, d())).isTrue();
        QueueMatcher matcher = matcher();

        assertThat(matcher.matchQueueOnce(queue)).isTrue();

        assertThat(rig.depth(queue)).as("拿不到锁不报实值：自己那份记 0").isEqualTo(0.0);
        assertThat(plans()).as("拿不到锁不得弹组").isEmpty();
        assertThat(lockHolder(queue)).as("不得释放别的实例的锁").contains("other-instance");
        assertThat(queueMembers(queue)).hasSize(2);

        store().unlockQueue(queue, "other-instance", d());
        assertThat(matcher.matchQueueOnce(queue)).isTrue();
        assertThat(rig.depth(queue)).isEqualTo(2.0);
        assertThat(plans()).hasSize(1);
        assertThat(plans().get(0).members()).hasSize(2);
        assertThat(lockHolder(queue)).isEmpty();
    }

    @Test
    void 空队列被剔除时深度归零() {
        QueueRef queue = newQueue(MODE_5V5);
        registerQueue(queue);

        assertThat(matcher().matchQueueOnce(queue)).isTrue();

        assertThat(indexed(queue)).as("空队列必须被剔除").isFalse();
        assertThat(rig.depth(queue)).as("剔除后 gauge 是 0").isEqualTo(0.0);
    }

    // ================================================================ Java 新增：凑不满的队列里的残项（基线没有，matcher.go:277-284 只剔空队列）

    /**
     * 票据没了（过期 / 被按票号删掉）而队列项还在：这条队列凑不满，从不走挑组里的成员校验，残项留着它就永远非空、永远不出注册集。
     * 凑单对凑不满的队列隔一段时间做一次只看票据的清理。
     */
    @Test
    void 凑不满的队列里没有票的残项_满一个间隔后被清掉_有效的排队者不动_清空的队列出注册集() {
        QueueRef mixed = newQueue(MODE_5V5);
        QueueRef dead = newQueue(MODE_1V1);
        long[] p = newPlayers(3);
        String gone = join(p[0], mixed, 150_000);
        join(p[1], mixed, 160_000);
        String expired = join(p[2], dead, 150_000);
        // 按票号删票不摘队列项（同票据到期）
        assertThat(store().delete(new TicketRef(p[0], gone), d())).isTrue();
        assertThat(store().delete(new TicketRef(p[2], expired), d())).isTrue();
        assertThat(queueMembers(mixed)).containsExactly(str(p[0]), str(p[1]));
        assertThat(queueMembers(dead)).containsExactly(str(p[2]));
        QueueMatcher matcher = matcher();

        assertThat(matcher.matchQueueOnce(mixed)).isTrue();
        assertThat(matcher.matchQueueOnce(dead)).isTrue();
        assertThat(queueMembers(mixed)).as("第一次见到只计时").hasSize(2);
        assertThat(queueMembers(dead)).hasSize(1);

        rig.nanos.addAndGet(QueueMatcher.STALE_SWEEP_INTERVAL_NANOS);
        assertThat(matcher.matchQueueOnce(mixed)).isTrue();
        assertThat(matcher.matchQueueOnce(dead)).isTrue();

        assertThat(queueMembers(mixed)).as("只摘没有票的那一项").containsExactly(str(p[1]));
        assertMirrorConsistent(mixed);
        assertThat(ticket(p[1]).state()).isEqualTo(TicketState.QUEUED);
        assertThat(indexed(mixed)).isTrue();
        assertThat(queueMembers(dead)).isEmpty();
        assertMirrorConsistent(dead);
        assertThat(indexed(dead)).as("死队列清空后当场出注册集").isFalse();
        assertThat(lockHolder(mixed)).isEmpty();
        assertThat(lockHolder(dead)).isEmpty();
        assertThat(plans()).isEmpty();
    }
}
