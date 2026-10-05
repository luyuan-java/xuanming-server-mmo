package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.PlayerState;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.SceneInfoComp;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.CrossNodeSwitch;
import com.game.scene.world.PlayerInitializer;
import com.game.scene.world.PlayerLocations;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneWorld;
import io.netty.channel.DefaultEventLoop;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * 停服与在途跨节点交出交叠（scene-handoff-spec §5.2、§5.5 停服行、§10.3）：真逻辑线程、真存储线程池（AbortPolicy）、
 * 真 {@link StoragePlayerRepository} 与真 {@link SceneWorld}，按 {@link SceneNode} 的停服顺序走 {@link SceneShutdown}。
 * 交出在停服写回之后才提交（写回 E 被围栏拒），结局送回仍活着的逻辑线程、要释放 E+1——这笔释放必须赶在关池之前提交。
 * 回归：过去写回一交出去就关池，结局回来时释放被已关闭的池拒绝（E+1 悬空到租约过期，还报一条「需人工修复」的写丢失），
 * 结局不明时的探测也被拒、判成失去。
 */
class SceneShutdownHandOffTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long E = 5;
    private static final int LOCAL_NODE = 3;
    private static final int TARGET_NODE = 4;
    private static final long REMOTE_SCENE = 900_001;
    /** 交出在写回之后、关池之后（旧顺序下）才提交：放行后再拖一会儿，让旧实现必然先关池。 */
    private static final long COMMIT_DELAY_MS = 300;

    private final DefaultEventLoop logic = new DefaultEventLoop();
    private final ThreadPoolExecutor storage = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100));
    private final PlayerStore store = mock(PlayerStore.class);
    private final FakeSwitchTargets targets = new FakeSwitchTargets();
    private final RecordingSink sink = new RecordingSink();
    /** 放行交出事务：停服写回执行完之后才放。 */
    private final CountDownLatch commitGate = new CountDownLatch(1);
    /** 交出每次尝试写下的新租约值。 */
    private final List<Long> attemptedLeases = new CopyOnWriteArrayList<>();
    private StoragePlayerRepository repository;
    private SceneWorld world;
    private ClientRequestHandler handler;

    @BeforeEach
    void setUp() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(PLAYER);
        row.setOwnerEpoch(E);
        row.setClassId(3);
        row.setGender(1);
        row.setAppearanceId("look");
        row.setLevel(1);
        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(row));
        when(store.loadState(PLAYER)).thenReturn(PlayerState.getDefaultInstance());
        // 交出先提交：停服写回 E 被围栏拒
        when(store.saveStateAndRelease(any(), any())).thenReturn(false);
        when(store.releaseOwnership(anyLong(), anyLong())).thenReturn(true);
        repository = new StoragePlayerRepository(store, storage, logic, SceneMetrics.noop());
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repository,
                new AtomicLong(5000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE,
                new CrossNodeSwitch(LOCAL_NODE, targets, owned -> { }, Duration.ofSeconds(4), Duration.ofSeconds(30)));
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
    }

    @AfterEach
    void tearDown() {
        commitGate.countDown();
        logic.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        storage.shutdownNow();
    }

    @Test
    void 停服时冻结中_交出在写回之后才提交_结局回到逻辑线程释放E加1_赶在关池之前_不计写丢失() throws Exception {
        when(store.handOffOwnership(any(), any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            commitGate.await(10, TimeUnit.SECONDS);
            attemptedLeases.add(inv.<Long>getArgument(2));
            Thread.sleep(COMMIT_DELAY_MS);
            return new HandOffResult.HandedOff(E + 1);
        });
        enterAndFreeze();

        SceneShutdown.Result result = shutdown();

        assertThat(result).isEqualTo(new SceneShutdown.Result(true, 1, 0));
        verify(store).releaseOwnership(PLAYER, E + 1);
        assertThat(repository.writeFailures()).as("释放没有撞上已关闭的池").isZero();
        assertThat(sink.transfers()).as("实例已被停服写回移出，不发 PlayerTransfer").isEmpty();
        assertThat(storage.isTerminated()).isTrue();
    }

    @Test
    void 停服时冻结中_交出结局不明_探测仍能提交_认出已提交后释放E加1() throws Exception {
        when(store.handOffOwnership(any(), any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            commitGate.await(10, TimeUnit.SECONDS);
            attemptedLeases.add(inv.<Long>getArgument(2));
            // 其实第一次已提交、应答丢了；之后的重试都超时：重试用尽 → 结局不明 → 探测
            throw new QueryTimeoutException("应答丢失");
        });
        when(store.probeOwnership(eq(PLAYER), any())).thenAnswer(
                inv -> Optional.of(new OwnerState(E + 1, false, attemptedLeases.get(0))));
        enterAndFreeze();

        SceneShutdown.Result result = shutdown();

        assertThat(result).isEqualTo(new SceneShutdown.Result(true, 1, 0));
        verify(store).probeOwnership(eq(PLAYER), any());
        verify(store).releaseOwnership(PLAYER, E + 1);
        assertThat(repository.writeFailures()).isZero();
        assertThat(storage.isTerminated()).isTrue();
    }

    @Test
    void 停服时冻结中_写回先提交_交出被围栏拒_不释放任何东西() throws Exception {
        when(store.handOffOwnership(any(), any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            commitGate.await(10, TimeUnit.SECONDS);
            Thread.sleep(COMMIT_DELAY_MS);
            return new HandOffResult.Fenced(new OwnerState(E, true, 0));
        });
        enterAndFreeze();

        SceneShutdown.Result result = shutdown();

        assertThat(result).isEqualTo(new SceneShutdown.Result(true, 1, 0));
        verify(store, never()).releaseOwnership(anyLong(), anyLong());
        assertThat(repository.writeFailures()).isZero();
    }

    /** 按 {@link SceneNode#stop} 的顺序：逻辑线程上写回（之后放行交出事务）→ 等在途交出处理完 → 关存储池并等落库。 */
    private SceneShutdown.Result shutdown() {
        return SceneShutdown.writeBackThenDrainStorage(logic, () -> {
            int count = world.shutdown();
            commitGate.countDown();
            return count;
        }, repository::awaitTransfersSettled, storage, Duration.ofSeconds(10), () -> 1, System::nanoTime);
    }

    /** 进场（异步加载经存储线程回到逻辑线程）→ 63 指定别的节点上的场景 → 选目标回「别的节点」→ 冻结并提交交出（交出卡在闸上）。 */
    private void enterAndFreeze() throws Exception {
        Scene scene = onLogic(() -> world.createScene(1));
        onLogic(() -> {
            world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                    .setSessionId(SESSION)
                    .setPlayerId(PLAYER)
                    .setSceneId(scene.sceneId())
                    .setOwnerEpoch(E)
                    .build());
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (onLogic(() -> world.playerById(PLAYER)) == null) {
            assertThat(System.nanoTime()).as("进场加载超时").isLessThan(deadline);
            Thread.sleep(10);
        }
        onLogic(() -> {
            handler.onClientForward(LINK, ClientForward.newBuilder()
                    .setSessionId(SESSION)
                    .setPlayerId(PLAYER)
                    .setMessageId(Contracts.IDS.enterScene())
                    .setBody(EnterSceneC2SRequest.newBuilder()
                            .setSceneInfo(SceneInfoComp.newBuilder().setSceneId(REMOTE_SCENE))
                            .build()
                            .toByteString())
                    .setRequestId(1)
                    .build());
            targets.take().chosen(TARGET_NODE, REMOTE_SCENE, 2);
            return null;
        });
        assertThat(onLogic(() -> world.playerById(PLAYER).frozen())).isTrue();
    }

    private <T> T onLogic(Callable<T> task) throws Exception {
        return logic.submit(task).get(5, TimeUnit.SECONDS);
    }
}
