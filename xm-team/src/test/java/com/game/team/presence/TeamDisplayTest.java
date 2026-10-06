package com.game.team.presence;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.view.MemberDisplay;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * 展示缓存（team-spec §4.3 / §6.7；scene-battle-spec §2.4 队伍视图行、§7.13 世界内部第 4 条）：资料读 player 表、在线读在线目录、
 * in_battle 由战斗锁的批量读算出（批次 6.3，team-spec D10 已收口）；任何读失败只填零值 / false，从不抛出。
 * 三路读都是替身；真 Redis 上的锁见 {@link TeamDisplayRedisIntegrationTest}，装配见 {@code TeamConfigurationTest}。
 * 类上的超时是兜底：有人把「限时等待」改成无限等待时，挂起的那几条用例应当失败而不是把构建卡住。
 * 必须是 {@code SEPARATE_THREAD}：缺省的 {@code SAME_THREAD} 到时只对测试线程发一次中断，而无限等待最顺手的写法
 * {@code CompletableFuture.join()} 不响应中断，用例会一直卡着、构建既不失败也不结束；独立线程模式下测试体在另一条线程上跑，
 * 到时由主线程直接判失败（卡住的那条线程留在原地，surefire 跑完照常退出）。日志捕获是实例字段加全局 logger，不依赖线程本地状态。
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TeamDisplayTest {

    private static final long A = Long.MIN_VALUE + 1;
    private static final long B = 2;
    private static final long C = Long.MIN_VALUE + 3;

    /** 谁都没有战斗锁。 */
    private static final Function<Collection<Long>, CompletionStage<Map<Long, Boolean>>> NO_LOCKS =
            ids -> CompletableFuture.completedFuture(Map.of());
    /** 谁都不在线。 */
    private static final Function<Collection<Long>, CompletionStage<Map<Long, PlayerPresence>>> NOBODY_ONLINE =
            ids -> CompletableFuture.completedFuture(Map.of());

    private final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TeamDisplay.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private static Map<Long, PlayerPresence> online(long... ids) {
        Map<Long, PlayerPresence> out = new HashMap<>();
        for (long id : ids) {
            out.put(id, PlayerPresence.newBuilder().setPlayerId(id).build());
        }
        return out;
    }

    /** WARN 及以上的日志行（格式化后的文本）。 */
    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
    }

    @Test
    void 资料与在线拼成展示缓存_去零去重() {
        List<List<Long>> asked = new ArrayList<>();
        TeamDisplay display = new TeamDisplay((ids, d) -> {
            asked.add(ids);
            return Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3));
        }, ids -> CompletableFuture.completedFuture(online(B)), NO_LOCKS);
        Map<Long, MemberDisplay> dc = display.load(List.of(A, 0L, B, A), Deadline.after(1000));
        assertThat(asked).containsExactly(List.of(A, B));
        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)),
                Map.entry(B, new MemberDisplay(true, false, 0, 0, "", "", 0)));
        assertThat(display.load(List.of(0L), Deadline.after(1000))).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void 资料或在线读失败都只填零值() {
        // 三路一起坏：资料、在线、战斗锁都读不到，仍然每人一份零值、不抛出
        TeamDisplay broken = new TeamDisplay((ids, d) -> {
            throw new IllegalStateException("mysql down");
        }, ids -> CompletableFuture.failedFuture(new IllegalStateException("redis down")),
                ids -> CompletableFuture.failedFuture(new IllegalStateException("redis down")));
        assertThat(broken.load(List.of(A), Deadline.after(1000))).containsExactly(Map.entry(A, MemberDisplay.NONE));
        TeamDisplay hang = new TeamDisplay((ids, d) -> Map.of(), ids -> new CompletableFuture<>(), NO_LOCKS);
        assertThat(hang.load(List.of(A), Deadline.after(20))).containsExactly(Map.entry(A, MemberDisplay.NONE));
        TeamDisplay throwing = new TeamDisplay((ids, d) -> Map.of(), ids -> {
            throw new IllegalStateException("closed");
        }, NO_LOCKS);
        assertThat(throwing.load(List.of(A), Deadline.after(1000))).containsExactly(Map.entry(A, MemberDisplay.NONE));
    }

    @Test
    void 字符串为null时按空串() {
        assertThat(new MemberDisplay(false, false, 0, 0, null, null, 0)).isEqualTo(MemberDisplay.NONE);
    }

    // ------------------------------------------------------------------ in_battle（scene-battle-spec §2.4、§7.13 第 4 条）

    @Test
    void 战斗锁在的人in_battle为true_锁不在或结果里没有的人为false_其余字段不受影响() {
        Map<Long, Boolean> locks = new HashMap<>();
        locks.put(A, true);
        locks.put(B, false);
        // C 不在结果里；再放一个没问过的 id，不应该冒出来
        locks.put(77L, true);
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3), C, new Profile(C, "丙", 9, 4, 2, "", 3)),
                ids -> CompletableFuture.completedFuture(online(A, B)),
                ids -> CompletableFuture.completedFuture(locks));

        Map<Long, MemberDisplay> dc = display.load(List.of(A, B, C), Deadline.after(1000));

        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(true, true, 31, 2, "甲", "ap", 1)),
                Map.entry(B, new MemberDisplay(true, false, 0, 0, "", "", 0)),
                Map.entry(C, new MemberDisplay(false, false, 9, 4, "丙", "", 2)));
        assertThat(warnings()).as("读成功不记告警").isEmpty();
    }

    @Test
    void 离线且没有资料的人只要锁在也是in_battle() {
        // 三个来源互相独立：in_battle 不以在线 / 有资料为前提（基线三次 MGET 各算各的）
        TeamDisplay display = new TeamDisplay((ids, d) -> Map.of(), NOBODY_ONLINE,
                ids -> CompletableFuture.completedFuture(Map.of(A, true)));
        assertThat(display.load(List.of(A, B), Deadline.after(1000))).containsExactly(
                Map.entry(A, new MemberDisplay(false, true, 0, 0, "", "", 0)),
                Map.entry(B, MemberDisplay.NONE));
    }

    @Test
    void 结果里的null值按false_不抛出() {
        Map<Long, Boolean> locks = new HashMap<>();
        locks.put(A, null);
        locks.put(B, true);
        TeamDisplay display = new TeamDisplay((ids, d) -> Map.of(), NOBODY_ONLINE, ids -> CompletableFuture.completedFuture(locks));
        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(1000));
        assertThat(dc.get(A).inBattle()).isFalse();
        assertThat(dc.get(B).inBattle()).isTrue();
    }

    @Test
    void 批量读战斗锁只发一次_带上去零去重后的全体玩家_空名单不发() {
        List<List<Long>> asked = new ArrayList<>();
        TeamDisplay display = new TeamDisplay((ids, d) -> Map.of(), NOBODY_ONLINE, ids -> {
            asked.add(List.copyOf(ids));
            return CompletableFuture.completedFuture(Map.of(C, true));
        });

        Map<Long, MemberDisplay> dc = display.load(List.of(A, 0L, B, A, C, B), Deadline.after(1000));

        assertThat(asked).as("一次视图构建只发一次批量读，不是逐人各发一次").containsExactly(List.of(A, B, C));
        assertThat(dc.keySet()).containsExactly(A, B, C);
        assertThat(dc.get(C).inBattle()).isTrue();

        asked.clear();
        assertThat(display.load(List.of(0L, 0L), Deadline.after(1000))).isEmpty();
        assertThat(display.load(List.of(), Deadline.after(1000))).isEmpty();
        assertThat(display.load(null, Deadline.after(1000))).isEmpty();
        assertThat(asked).as("没有人可读时不碰 Redis").isEmpty();
    }

    @Test
    void 在线读与战斗锁读都先于阻塞的资料读发出_资料读期间才完成的结果照样用上() {
        List<String> order = new ArrayList<>();
        CompletableFuture<Map<Long, PlayerPresence>> onlineRead = new CompletableFuture<>();
        CompletableFuture<Map<Long, Boolean>> lockRead = new CompletableFuture<>();
        TeamDisplay display = new TeamDisplay((ids, d) -> {
            order.add("profiles");
            // 两路 Redis 读此刻都已发出、还没回来；在阻塞的资料读「期间」完成它们
            assertThat(onlineRead).isNotDone();
            assertThat(lockRead).isNotDone();
            lockRead.complete(Map.of(A, true));
            onlineRead.complete(online(B));
            return Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3));
        }, ids -> {
            order.add("presence");
            return onlineRead;
        }, ids -> {
            order.add("battleLocks");
            return lockRead;
        });

        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(1000));

        assertThat(order).containsExactly("presence", "battleLocks", "profiles");
        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(false, true, 31, 2, "甲", "ap", 1)),
                Map.entry(B, new MemberDisplay(true, false, 0, 0, "", "", 0)));
    }

    @Test
    void 战斗锁读晚到但在预算内_等到结果() {
        // 资料读已经返回之后才完成：load 必须真的等它，而不是「没完成就当 false」
        TeamDisplay display = new TeamDisplay((ids, d) -> Map.of(), NOBODY_ONLINE,
                ids -> CompletableFuture.supplyAsync(() -> Map.of(A, true), CompletableFuture.delayedExecutor(60, TimeUnit.MILLISECONDS)));
        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(5000));
        assertThat(dc.get(A).inBattle()).isTrue();
        assertThat(dc.get(B).inBattle()).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void 战斗锁读整体异常完成_全部按false_在线与资料照常_只记一行WARN() {
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3)),
                ids -> CompletableFuture.completedFuture(online(A, B)),
                ids -> CompletableFuture.failedFuture(new IllegalStateException("redis down")));

        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(1000));

        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(true, false, 31, 2, "甲", "ap", 1)),
                Map.entry(B, new MemberDisplay(true, false, 0, 0, "", "", 0)));
        assertThat(warnings()).singleElement().satisfies(line -> assertThat(line)
                .startsWith("WARN ").contains("战斗锁").contains("2 人").contains("in_battle 按 false")
                .contains("IllegalStateException: redis down"));
    }

    @Test
    void 战斗锁读调用时同步抛出_全部按false_不抛出_只记一行WARN() {
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3)),
                ids -> CompletableFuture.completedFuture(online(B)),
                ids -> {
                    throw new IllegalStateException("Redisson is shutdown");
                });

        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(1000));

        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)),
                Map.entry(B, new MemberDisplay(true, false, 0, 0, "", "", 0)));
        assertThat(warnings()).singleElement().satisfies(line -> assertThat(line)
                .startsWith("WARN ").contains("战斗锁").contains("Redisson is shutdown"));
    }

    @Test
    void 战斗锁读返回null或以null完成_全部按false_不抛出_各记一行WARN() {
        TeamDisplay nullStage = new TeamDisplay((ids, d) -> Map.of(), ids -> CompletableFuture.completedFuture(online(A)), ids -> null);
        assertThat(nullStage.load(List.of(A), Deadline.after(1000)))
                .containsExactly(Map.entry(A, new MemberDisplay(true, false, 0, 0, "", "", 0)));
        assertThat(warnings()).singleElement().satisfies(line -> assertThat(line).startsWith("WARN ").contains("战斗锁"));

        logs.list.clear();
        TeamDisplay nullResult = new TeamDisplay((ids, d) -> Map.of(), ids -> CompletableFuture.completedFuture(online(A)),
                ids -> CompletableFuture.completedFuture(null));
        assertThat(nullResult.load(List.of(A), Deadline.after(1000)))
                .containsExactly(Map.entry(A, new MemberDisplay(true, false, 0, 0, "", "", 0)));
        assertThat(warnings()).singleElement().satisfies(line -> assertThat(line).startsWith("WARN ").contains("战斗锁").contains("结果为空"));
    }

    @Test
    void 战斗锁读挂起_等到请求预算用完就放弃_全部按false_视图照常返回_只记一行WARN() {
        CompletableFuture<Map<Long, Boolean>> never = new CompletableFuture<>();
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3)),
                ids -> CompletableFuture.completedFuture(online(A)),
                ids -> never);

        long startedAt = System.nanoTime();
        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(80));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(true, false, 31, 2, "甲", "ap", 1)),
                Map.entry(B, MemberDisplay.NONE));
        assertThat(tookMs).as("限时等待：等满预算（80 ms）就返回，不会一直挂着").isBetween(80L, 3000L);
        assertThat(warnings()).singleElement().satisfies(line -> assertThat(line)
                .startsWith("WARN ").contains("战斗锁").contains("超过请求预算").contains("TimeoutException"));

        // 放弃之后才回来的结果不影响已经返回的视图
        never.complete(Map.of(A, true));
        assertThat(dc.get(A).inBattle()).isFalse();
    }

    @Test
    void 在线读把预算耗尽_已经完成的战斗锁读照样取到结果() {
        TeamDisplay display = new TeamDisplay((ids, d) -> Map.of(), ids -> new CompletableFuture<>(),
                ids -> CompletableFuture.completedFuture(Map.of(A, true)));

        Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(40));

        assertThat(dc).containsExactly(
                Map.entry(A, new MemberDisplay(false, true, 0, 0, "", "", 0)),
                Map.entry(B, MemberDisplay.NONE));
        assertThat(warnings()).as("只有在线读超时那一行，战斗锁读没有失败").singleElement()
                .satisfies(line -> assertThat(line).contains("在线目录").doesNotContain("战斗锁"));
    }

    @Test
    void 预算一开始就用完_两路都没回来_全部按离线与不在战斗_不抛出() {
        TeamDisplay display = new TeamDisplay((ids, d) -> Map.of(), ids -> new CompletableFuture<>(), ids -> new CompletableFuture<>());
        assertThat(display.load(List.of(A), Deadline.after(0))).containsExactly(Map.entry(A, MemberDisplay.NONE));
        assertThat(warnings()).hasSize(2);
    }

    // ------------------------------------------------------------------ 等待被中断（TeamDisplay.awaitBattleLocks 列的第六种失败形态）

    @Test
    void 等战斗锁读时线程已被中断_立刻按false返回_不抛出_不把预算等完_中断标志保留_只记一行WARN() {
        // 在线读已经完成：中断不影响已完成的那一路，照样取到结果；只有还没回来的战斗锁读按 false
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3)),
                ids -> CompletableFuture.completedFuture(online(A)),
                ids -> new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            long startedAt = System.nanoTime();
            Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(5000));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(dc).containsExactly(
                    Map.entry(A, new MemberDisplay(true, false, 31, 2, "甲", "ap", 1)),
                    Map.entry(B, MemberDisplay.NONE));
            assertThat(tookMs).as("被中断就立刻放弃，不把剩下的 5 s 预算等完").isLessThan(1000L);
            assertThat(Thread.currentThread().isInterrupted()).as("中断标志留给线程池，不能被吞掉").isTrue();
            assertThat(warnings()).singleElement().satisfies(line -> assertThat(line)
                    .startsWith("WARN ").contains("战斗锁").contains("2 人").contains("in_battle 按 false")
                    .contains("被中断").contains("InterruptedException"));
        } finally {
            // 清掉标志，免得带到后面的用例（SAME_THREAD 模式下尤其）
            Thread.interrupted();
        }
    }

    /** 工作线程上一次 load 的结果：视图、返回时线程的中断标志。 */
    private record Loaded(Map<Long, MemberDisplay> display, boolean interrupted) {
    }

    @Test
    void 工作线程停在等待里被shutdownNow中断_两路都立刻放弃_视图照常返回_中断标志留给线程池() throws Exception {
        // 生产里的来源：工作线程池排空超时后 shutdownNow，打断的是正停在限时等待里的线程。两路都还没回来：
        // 第一路（在线）被打断后中断标志必须恢复，第二路（战斗锁）才会同样立刻放弃——标志被吞掉的话它会把剩下的 10 s 预算等完
        CountDownLatch loading = new CountDownLatch(1);
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(A, new Profile(A, "甲", 31, 2, 1, "ap", 3)),
                ids -> new CompletableFuture<>(),
                ids -> {
                    loading.countDown();
                    return new CompletableFuture<>();
                });
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Loaded> task = pool.submit(() -> {
                worker.set(Thread.currentThread());
                Map<Long, MemberDisplay> dc = display.load(List.of(A, B), Deadline.after(10_000));
                return new Loaded(dc, Thread.currentThread().isInterrupted());
            });
            assertThat(loading.await(5, TimeUnit.SECONDS)).as("load 已经在工作线程上发出两路读").isTrue();
            // 等它真的停进限时等待（最多 5 s；没等到也照常往下走，结论由下面的断言定）
            long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (worker.get().getState() != Thread.State.TIMED_WAITING && System.nanoTime() < giveUpAt) {
                Thread.sleep(1);
            }
            assertThat(task).as("中断之前 load 还挂在等待里").isNotDone();

            long interruptedAt = System.nanoTime();
            pool.shutdownNow();
            Loaded loaded = task.get(8, TimeUnit.SECONDS);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - interruptedAt);

            assertThat(loaded.display()).as("不抛出：资料照常，在线与 in_battle 按 false").containsExactly(
                    Map.entry(A, new MemberDisplay(false, false, 31, 2, "甲", "ap", 1)),
                    Map.entry(B, MemberDisplay.NONE));
            assertThat(tookMs).as("两路都立刻放弃，没有哪一路把剩下的约 10 s 预算等完").isLessThan(3000L);
            assertThat(loaded.interrupted()).as("中断标志留给线程池，不能被吞掉").isTrue();
            assertThat(warnings()).as("在线读、战斗锁读各一行，次序与等待次序一致").hasSize(2);
            assertThat(warnings().get(0)).startsWith("WARN ").contains("在线目录").contains("被中断").doesNotContain("战斗锁");
            assertThat(warnings().get(1)).startsWith("WARN ").contains("战斗锁").contains("被中断").contains("InterruptedException");
        } finally {
            pool.shutdownNow();
        }
    }
}
