package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerPusher;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.ManualRedisClock;
import com.game.match.testing.RecordingPushes;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.RespondChallengeRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code match-push} 执行器与切磋服务在真线程上的配合（match-spec §9.3）：任务跑在名为 {@code match-push-N} 的线程上；队列满或已关闭时退回提交线程
 * 执行、不丢任务（否则等在回调后面的 future 永远不完成）；156 的结果跨线程等得到；gather 在别的线程上结束时，失败通知是在 {@code match-push} 上推的，
 * 不占完成 gather 的那条线程。
 */
class MatchPushExecutorTest {

    private static final long A = 1001;
    private static final long B = 1002;

    private final MatchPushExecutor executor = new MatchPushExecutor();

    @AfterEach
    void close() {
        executor.close();
    }

    @Test
    void 任务跑在match_push线程上_线程池指标的name标签是match_push() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            executor.execute(() -> {
                threads.add(Thread.currentThread().getName());
                done.countDown();
            });
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threads).allMatch(name -> name.startsWith("match-push-"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        executor.bindTo(meters);
        assertThat(meters.get("executor.pool.core").tag("name", "match-push").gauge().value()).isEqualTo(MatchPushExecutor.THREADS);
    }

    @Test
    void 队列满了_多出来的任务在提交线程上执行_一个都不丢() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(MatchPushExecutor.THREADS + MatchPushExecutor.QUEUE);
        Runnable blocked = () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        };
        for (int i = 0; i < MatchPushExecutor.THREADS + MatchPushExecutor.QUEUE; i++) {
            executor.execute(blocked);
        }
        List<String> ranOn = new CopyOnWriteArrayList<>();

        executor.execute(() -> ranOn.add(Thread.currentThread().getName()));

        assertThat(ranOn).as("没地方放了：就在提交的线程上跑").containsExactly(Thread.currentThread().getName());
        release.countDown();
        assertThat(finished.await(10, TimeUnit.SECONDS)).as("排着队的任务照常跑完").isTrue();
    }

    @Test
    void 关闭之后提交的任务也在提交线程上执行_不悬着() {
        executor.close();
        List<String> ranOn = new CopyOnWriteArrayList<>();

        executor.execute(() -> ranOn.add(Thread.currentThread().getName()));

        assertThat(ranOn).containsExactly(Thread.currentThread().getName());
    }

    @Test
    void 切磋接在真的执行器上_156跨线程等得到_gather在别的线程结束时失败通知由match_push推() throws Exception {
        FakePlayerStatus players = new FakePlayerStatus().online(A, 1, 7).online(B, 1, 7);
        RecordingPushes pushes = new RecordingPushes();
        List<String> pushThreads = new CopyOnWriteArrayList<>();
        PlayerPusher pusher = (playerId, content) -> {
            pushThreads.add(Thread.currentThread().getName());
            return pushes.push(playerId, content);
        };
        FakeGatherLauncher gather = new FakeGatherLauncher().hold();
        ChallengeService service = new ChallengeService(players, new InMemoryChallengeStore(new ManualRedisClock()),
                new MatchIds(new Snowflake(5), () -> true, () -> false), pusher, gather,
                new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false)), executor, 60_000, 156, 154);
        SessionContext a = SessionContext.newBuilder().setPlayerId(A).setAccount("acc-a").build();
        SessionContext b = SessionContext.newBuilder().setPlayerId(B).setAccount("acc-b").build();

        long challengeId = service.challenge(a, ChallengePlayerRequest.newBuilder().setTargetPlayerId(B).build(), Deadline.after(3_000))
                .getChallengeId();
        assertThat(challengeId).as("156 的结果经 match-push 回到工作线程").isNotZero();
        assertThat(service.respond(b, RespondChallengeRequest.newBuilder().setChallengeId(challengeId).setAccept(true).build(), Deadline.after(3_000))
                .hasErrorMessage()).isFalse();
        assertThat(pushes.sent).hasSize(3);

        Thread gatherThread = new Thread(() -> gather.complete(0, GatherResult.failed(GatherOutcome.NO_BATTLE_NODE, 0)), "fake-gather");
        gatherThread.start();
        gatherThread.join(5_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pushes.sent.size() < 5 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }

        assertThat(pushes.sent).extracting(RecordingPushes.Pushed::playerId).containsExactly(B, A, B, A, B);
        assertThat(pushThreads.subList(0, 3)).as("请求里的三条推送在调用线程上发起").containsOnly(Thread.currentThread().getName());
        assertThat(pushThreads.subList(3, 5)).as("失败通知不在完成 gather 的线程上推").allMatch(name -> name.startsWith("match-push-"));
    }
}
