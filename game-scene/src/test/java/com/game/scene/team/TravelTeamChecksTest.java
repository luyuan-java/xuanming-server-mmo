package com.game.scene.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.scene.testing.CapturedLogs;
import com.game.scene.world.TeamChecks.TeamCheck;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 226 受理前的在队检查（批次 5.4，zone-travel-spec §5.5 第 5 步、X4、§11.5「延迟应答」与「PRECHECK 过期」的适配层部分）：
 * 读结果的三态映射、读口套的上限（到时 UNKNOWN，迟到的读结果不再投递）、结果只经逻辑执行器投递（恰好一次、不在 {@code check} 的调用栈内、
 * 从别的线程完成时也回到逻辑线程），逻辑线程已停时丢弃（回调在哪条线程上都不执行）。读口是假的（测试决定何时、在哪条线程上、以什么结果完成）。
 */
class TravelTeamChecksTest {

    private static final long PLAYER = 0x8000_0000_0000_1001L;   // ≥ 2^63：id 一律按无符号处理
    private static final Duration S2 = Duration.ofSeconds(2);

    /** 假的「逻辑线程」：只收任务（任意线程可投），测试线程自己取出来跑。 */
    private final LinkedBlockingQueue<Runnable> logicQueue = new LinkedBlockingQueue<>();
    private final Executor logic = logicQueue::add;
    private final AtomicInteger reads = new AtomicInteger();

    private TravelTeamChecks checks(CompletionStage<TeamMembership> reply) {
        return new TravelTeamChecks(playerId -> {
            assertThat(playerId).isEqualTo(PLAYER);
            reads.incrementAndGet();
            return reply;
        }, logic);
    }

    /** 检查一次，等结果投递进逻辑队列后在测试线程上执行；断言回调不在调用栈内、恰好一次。 */
    private TeamCheck checkAndAwait(TravelTeamChecks checks, Duration timeout) throws InterruptedException {
        List<TeamCheck> results = new CopyOnWriteArrayList<>();
        checks.check(PLAYER, timeout, results::add);
        assertThat(results).as("回调不在 check 的调用栈内").isEmpty();
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).as("结果投递到了逻辑执行器").isNotNull();
        assertThat(results).as("回调不在完成读的线程上直接跑").isEmpty();
        task.run();
        assertThat(logicQueue.poll(50, TimeUnit.MILLISECONDS)).as("恰好一次").isNull();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void 不在队_索引键缺失或tid为0_回NOT_IN_TEAM() throws Exception {
        assertThat(checkAndAwait(checks(CompletableFuture.completedFuture(TeamMembership.KEY_MISSING)), S2))
                .isEqualTo(TeamCheck.NOT_IN_TEAM);
        assertThat(checkAndAwait(checks(CompletableFuture.completedFuture(new TeamMembership(0, 7, false, null))), S2))
                .as("已离队但保留 epoch").isEqualTo(TeamCheck.NOT_IN_TEAM);
        assertThat(reads.get()).as("每次都现读，不缓存").isEqualTo(2);
    }

    @Test
    void 在队_带投影或投影缺失_都回IN_TEAM() throws Exception {
        TeamInfo info = TeamInfo.newBuilder().setTeamId(0x8000_0000_0000_0007L).setLeaderId(PLAYER).addMembers(PLAYER).build();
        assertThat(checkAndAwait(checks(CompletableFuture.completedFuture(
                new TeamMembership(0x8000_0000_0000_0007L, 3, false, info))), S2)).isEqualTo(TeamCheck.IN_TEAM);
        assertThat(checkAndAwait(checks(CompletableFuture.completedFuture(
                new TeamMembership(0x8000_0000_0000_0007L, 3, false, null))), S2))
                .as("投影缺失只是跟随不跟，人仍在队").isEqualTo(TeamCheck.IN_TEAM);
    }

    @Test
    void 读失败_数据损坏_读口同步抛出_读口给空future_一律回UNKNOWN() throws Exception {
        assertThat(checkAndAwait(checks(CompletableFuture.failedFuture(new IllegalStateException("组队索引 / 投影损坏"))), S2))
                .isEqualTo(TeamCheck.UNKNOWN);
        assertThat(checkAndAwait(checks(CompletableFuture.failedFuture(new RuntimeException("Redis 连接断开"))), S2))
                .isEqualTo(TeamCheck.UNKNOWN);
        assertThat(checkAndAwait(checks(CompletableFuture.completedFuture(null)), S2)).as("空结果").isEqualTo(TeamCheck.UNKNOWN);
        assertThat(checkAndAwait(checks(null), S2)).as("读口给了空 future").isEqualTo(TeamCheck.UNKNOWN);

        TravelTeamChecks throwing = new TravelTeamChecks(playerId -> {
            throw new IllegalStateException("读口同步抛出");
        }, logic);
        assertThat(checkAndAwait(throwing, S2)).isEqualTo(TeamCheck.UNKNOWN);
    }

    /**
     * 读口一直不回（Redis 卡住）：上限到时回 UNKNOWN（调用方回 3027）；之后读口才完成——结果不再投递（恰好一次），
     * 读口自己的 future 也不被这边的超时改动（等的是副本）。
     */
    @Test
    void 读口一直不回_到上限回UNKNOWN_迟到的读结果不再投递_读口的future不被改动() throws Exception {
        CompletableFuture<TeamMembership> hanging = new CompletableFuture<>();
        TravelTeamChecks checks = checks(hanging);
        List<TeamCheck> results = new CopyOnWriteArrayList<>();
        long started = System.nanoTime();

        checks.check(PLAYER, Duration.ofMillis(200), results::add);

        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(task).as("到上限投递了结果").isNotNull();
        assertThat(elapsedMs).as("由上限打断，而不是等读口").isBetween(150L, 4_000L);
        task.run();
        assertThat(results).containsExactly(TeamCheck.UNKNOWN);
        assertThat(hanging).as("读口自己的 future 没被这边的超时完成").isNotDone();

        hanging.complete(new TeamMembership(9, 1, false, null));
        assertThat(logicQueue.poll(200, TimeUnit.MILLISECONDS)).as("迟到的读结果不再投递").isNull();
        assertThat(results).containsExactly(TeamCheck.UNKNOWN);
    }

    @Test
    void 读结果在别的线程上完成_回调回到逻辑线程上执行() throws Exception {
        CompletableFuture<TeamMembership> pending = new CompletableFuture<>();
        TravelTeamChecks checks = checks(pending);
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        List<TeamCheck> results = new CopyOnWriteArrayList<>();
        checks.check(PLAYER, S2, result -> {
            ranOn.add(Thread.currentThread());
            results.add(result);
        });

        Thread redisson = new Thread(() -> pending.complete(TeamMembership.KEY_MISSING), "fake-redisson");
        redisson.start();
        redisson.join(5_000);

        assertThat(results).as("完成读的线程只做投递").isEmpty();
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).isNotNull();
        task.run();
        assertThat(results).containsExactly(TeamCheck.NOT_IN_TEAM);
        assertThat(ranOn).containsExactly(Thread.currentThread());
    }

    /**
     * 逻辑线程已停（zone-travel-spec §5.11）：投递被拒时结果丢弃、只记日志，回调不在完成读的线程上补跑——读口已完成时那是
     * {@code check} 的调用方（测试线程），否则是完成读的线程（假的 Redisson 线程）。回调里不能拿「抛 AssertionError」当断言：
     * 它跑在 {@code whenComplete} 里，抛出的东西会被 CompletableFuture 吞掉、谁也看不到，所以改为记下调用，等完成读的线程跑完
     * （{@code whenComplete} 的回调在完成 future 的那条线程上同步执行）再断言一次也没有；走的确实是丢弃分支，由日志确认。
     */
    @Test
    void 逻辑线程已停_投递被拒_结果丢弃只记日志_回调在哪条线程上都不执行() throws Exception {
        AtomicInteger offered = new AtomicInteger();
        Executor stopped = task -> {
            offered.incrementAndGet();
            throw new RejectedExecutionException("逻辑线程已停");
        };
        CompletableFuture<TeamMembership> later = new CompletableFuture<>();
        TravelTeamChecks immediate = new TravelTeamChecks(playerId -> CompletableFuture.completedFuture(TeamMembership.KEY_MISSING),
                stopped);
        TravelTeamChecks onOtherThread = new TravelTeamChecks(playerId -> later, stopped);
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        Consumer<TeamCheck> onDone = result -> ranOn.add(Thread.currentThread());

        try (CapturedLogs logs = new CapturedLogs(TravelTeamChecks.class)) {
            immediate.check(PLAYER, S2, onDone);
            onOtherThread.check(PLAYER, S2, onDone);
            Thread completer = new Thread(() -> later.complete(TeamMembership.KEY_MISSING), "fake-redisson");
            completer.start();
            completer.join(5_000);
            assertThat(completer.isAlive()).as("完成读的线程已跑完（回调随之跑完）").isFalse();

            assertThat(offered).as("两个结果各尝试投递一次").hasValue(2);
            assertThat(ranOn).as("投递被拒后回调没有在任何线程上补跑").isEmpty();
            assertThat(logs.count("丢弃在队检查的结果")).as("走的是丢弃分支（拒绝异常被接住）").isEqualTo(2);
        }
    }

    @Test
    void 上限不为正是编程错误_在读之前就拒() {
        TravelTeamChecks checks = checks(CompletableFuture.completedFuture(TeamMembership.KEY_MISSING));

        assertThatThrownBy(() -> checks.check(PLAYER, Duration.ZERO, result -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checks.check(PLAYER, Duration.ofMillis(-1), result -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reads.get()).isZero();
        assertThat(logicQueue).isEmpty();
        assertThatThrownBy(() -> new TravelTeamChecks(null, logic)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TravelTeamChecks(playerId -> null, null)).isInstanceOf(NullPointerException.class);
    }
}
