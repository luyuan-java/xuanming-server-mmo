package com.game.battle.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.room.RoomOrigin;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.battle.testing.StubBattleRoomService;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 控制面提供方（battle-node-spec §6.2、§7.8、§13.4）：createBattle 的节点级准入（not_started / closed / overloaded / closed_in_loop，
 * 全部零副作用）、业务结论放在应答字节里、提供方从不产出 UNSPECIFIED、其余四个方法的投递与传输失败、Dubbo 线程不碰房间、
 * future 在回复执行器上完成、指标每次调用恰好一次。
 */
class BattleNodeServiceImplTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final AdmissionGate admission = new AdmissionGate();
    private final ManualBattleScheduler logic = new ManualBattleScheduler(1_000_000L);
    private final StubBattleRoomService rooms = new StubBattleRoomService();

    private BattleNodeServiceImpl service(int maxInFlight) {
        return new BattleNodeServiceImpl(admission, logic, rooms, Runnable::run, maxInFlight, metrics);
    }

    private static CreateBattleRequest create(long battleId) {
        return CreateBattleRequest.newBuilder().setBattleId(battleId).build();
    }

    private double rpcCount(String method, String result) {
        var timer = registry.find("xm.battle.rpc").tag("method", method).tag("result", result).timer();
        return timer == null ? 0 : timer.count();
    }

    private double notAllocatableCreates() {
        return registry.get("xm.battle.room.creates").tag("result", "not_allocatable").counter().count();
    }

    @Test
    void 准入闸未开_NOT_ALLOCATABLE_not_started_不占名额不投递() throws Exception {
        BattleNodeServiceImpl service = service(8);

        CreateBattleResult result = service.createBattle(create(7)).get();

        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(result.getReason()).isEqualTo("not_started");
        assertThat(result.getResponse().isEmpty()).isTrue();
        logic.runPending();
        assertThat(rooms.calls).isEmpty();
        assertThat(service.inFlight()).isZero();
        assertThat(notAllocatableCreates()).isEqualTo(1);
        assertThat(rpcCount("createBattle", "not_allocatable")).isEqualTo(1);
    }

    @Test
    void 准入闸已关_NOT_ALLOCATABLE_closed() throws Exception {
        admission.open();
        admission.close();

        CreateBattleResult result = service(8).createBattle(create(7)).get();

        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(result.getReason()).isEqualTo("closed");
        logic.runPending();
        assertThat(rooms.calls).isEmpty();
    }

    @Test
    void 投递之后执行之前关闸_逻辑线程复核_closed_in_loop_零副作用() throws Exception {
        admission.open();
        BattleNodeServiceImpl service = service(8);

        CompletableFuture<CreateBattleResult> future = service.createBattle(create(7));
        assertThat(future).as("投递进逻辑线程，还没执行").isNotDone();
        admission.close();
        logic.runPending();

        CreateBattleResult result = future.get();
        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(result.getReason()).isEqualTo("closed_in_loop");
        assertThat(rooms.calls).isEmpty();
        assertThat(service.inFlight()).isZero();
        assertThat(notAllocatableCreates()).isEqualTo(1);
        assertThat(rpcCount("createBattle", "not_allocatable")).isEqualTo(1);
    }

    @Test
    void 在途超限_createBattle回overloaded_其余方法传输失败_完成后名额归还() throws Exception {
        admission.open();
        BattleNodeServiceImpl service = service(1);

        CompletableFuture<CreateBattleResult> holding = service.createBattle(create(1));
        assertThat(service.inFlight()).isEqualTo(1);
        CreateBattleResult overloaded = service.createBattle(create(2)).get();
        CompletableFuture<IssueBattleTicketResponse> ticket = service.issueBattleTicket(IssueBattleTicketRequest.getDefaultInstance());

        assertThat(overloaded.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(overloaded.getReason()).isEqualTo("overloaded");
        assertThatThrownBy(ticket::get).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(rpcCount("issueBattleTicket", "error")).isEqualTo(1);

        logic.runPending();
        assertThat(holding.get().getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(service.inFlight()).isZero();
        assertThat(rooms.calls).containsExactly("createBattle");
    }

    @Test
    void 逻辑线程拒绝投递_createBattle回closed_其余方法传输失败_名额归还() throws Exception {
        admission.open();
        BattleNodeServiceImpl service = service(8);
        logic.shutdown();

        CreateBattleResult result = service.createBattle(create(7)).get();
        CompletableFuture<Empty> destroy = service.destroyBattle(DestroyBattleRequest.getDefaultInstance());

        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(result.getReason()).isEqualTo("closed");
        assertThatThrownBy(destroy::get).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(service.inFlight()).isZero();
        assertThat(rpcCount("destroyBattle", "error")).isEqualTo(1);
    }

    @Test
    void 受理_应答是契约CreateBattleResponse字节_业务拒绝也在字节里_来源MATCH() throws Exception {
        admission.open();
        BattleNodeServiceImpl service = service(8);

        CompletableFuture<CreateBattleResult> ok = service.createBattle(create(7));
        logic.runPending();
        rooms.createTip = 1005;
        CompletableFuture<CreateBattleResult> rejected = service.createBattle(create(8));
        logic.runPending();

        CreateBattleResult okResult = ok.get();
        assertThat(okResult.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(okResult.getReason()).isEmpty();
        CreateBattleResponse okResponse = CreateBattleResponse.parseFrom(okResult.getResponse());
        assertThat(okResponse.getBattleId()).isEqualTo(7);
        assertThat(okResponse.hasErrorMessage()).isFalse();

        CreateBattleResult rejectedResult = rejected.get();
        assertThat(rejectedResult.getAdmission()).as("业务拒绝不是节点级拒绝").isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(rejectedResult.getResponse()).getErrorMessage().getId()).isEqualTo(1005);

        assertThat(rooms.origins).containsExactly(RoomOrigin.MATCH, RoomOrigin.MATCH);
        assertThat(rpcCount("createBattle", "ok")).isEqualTo(1);
        assertThat(rpcCount("createBattle", "business_error")).isEqualTo(1);
        assertThat(notAllocatableCreates()).as("业务结局由房间计，控制面只计 not_allocatable").isZero();
    }

    @Test
    void dev入口带房间来源DEV_走同一条准入路径() throws Exception {
        BattleNodeServiceImpl service = service(8);
        assertThat(service.createBattle(create(7), RoomOrigin.DEV).get().getReason()).isEqualTo("not_started");

        admission.open();
        CompletableFuture<CreateBattleResult> future = service.createBattle(create(7), RoomOrigin.DEV);
        logic.runPending();

        assertThat(future.get().getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(rooms.origins).containsExactly(RoomOrigin.DEV);
    }

    @Test
    void 提供方从不产出UNSPECIFIED() throws Exception {
        BattleNodeServiceImpl service = service(1);
        assertThat(service.createBattle(create(1)).get().getAdmission()).isNotEqualTo(BattleAdmission.BATTLE_ADMISSION_UNSPECIFIED);
        admission.open();
        CompletableFuture<CreateBattleResult> a = service.createBattle(create(2));
        CreateBattleResult b = service.createBattle(create(3)).get();
        logic.runPending();
        admission.close();
        CreateBattleResult c = service.createBattle(create(4)).get();
        for (CreateBattleResult r : new CreateBattleResult[] {a.get(), b, c}) {
            assertThat(r.getAdmission()).isNotEqualTo(BattleAdmission.BATTLE_ADMISSION_UNSPECIFIED);
        }
    }

    @Test
    void 处理中抛异常_future异常完成_名额归还_计error() {
        admission.open();
        BattleNodeServiceImpl service = service(8);
        rooms.beforeCall = method -> {
            throw new IllegalStateException("房间缺陷");
        };

        CompletableFuture<CreateBattleResult> create = service.createBattle(create(7));
        CompletableFuture<AddObserverResponse> observer = service.addObserver(AddObserverRequest.getDefaultInstance());
        logic.runPending();

        assertThatThrownBy(create::get).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(observer::get).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(service.inFlight()).isZero();
        assertThat(rpcCount("createBattle", "error")).isEqualTo(1);
        assertThat(rpcCount("addObserver", "error")).isEqualTo(1);
    }

    @Test
    void 其余四个方法_投递到逻辑线程_业务tip在应答里_不看准入闸() throws Exception {
        BattleNodeServiceImpl service = service(8);
        rooms.ticketTip = 1005;

        CompletableFuture<Empty> destroy = service.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(1).build());
        CompletableFuture<IssueBattleTicketResponse> ticket = service.issueBattleTicket(IssueBattleTicketRequest.getDefaultInstance());
        CompletableFuture<AddObserverResponse> observer = service.addObserver(AddObserverRequest.getDefaultInstance());
        CompletableFuture<Empty> remove = service.removeObserver(RemoveObserverRequest.getDefaultInstance());
        assertThat(rooms.calls).as("Dubbo 线程上不碰房间").isEmpty();
        logic.runPending();

        assertThat(destroy.get()).isEqualTo(Empty.getDefaultInstance());
        assertThat(ticket.get().getErrorMessage().getId()).isEqualTo(1005);
        assertThat(observer.get().getErrorMessage().getId()).isEqualTo(1005);
        assertThat(remove.get()).isEqualTo(Empty.getDefaultInstance());
        assertThat(rooms.calls).containsExactly("destroyBattle", "issueBattleTicket", "addObserver", "removeObserver");
        assertThat(rpcCount("destroyBattle", "ok")).isEqualTo(1);
        assertThat(rpcCount("issueBattleTicket", "business_error")).isEqualTo(1);
        assertThat(rpcCount("addObserver", "business_error")).isEqualTo(1);
        assertThat(rpcCount("removeObserver", "ok")).isEqualTo(1);
        assertThat(service.inFlight()).isZero();
    }

    @Test
    void 提供方线程不阻塞_房间只在逻辑线程上调_future在回复执行器上完成() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop((ThreadFactory) r -> new Thread(r, "test-battle-logic"));
        ExecutorService replies = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-battle-rpc-reply"));
        try {
            EventLoopBattleScheduler scheduler = new EventLoopBattleScheduler(loop);
            rooms.loop = scheduler;
            CountDownLatch release = new CountDownLatch(1);
            rooms.beforeCall = method -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            admission.open();
            BattleNodeServiceImpl service = new BattleNodeServiceImpl(admission, scheduler, rooms, replies, 8, metrics);

            CompletableFuture<CreateBattleResult> future = service.createBattle(create(7));
            assertThat(future).as("提供方线程立即返回，不等逻辑线程").isNotDone();
            AtomicReference<String> completedOn = new AtomicReference<>();
            CompletableFuture<Void> observed = future.whenComplete((r, e) -> completedOn.set(Thread.currentThread().getName()))
                    .thenAccept(r -> { });
            release.countDown();
            observed.get(5, TimeUnit.SECONDS);

            assertThat(future.get().getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
            assertThat(rooms.threads).containsExactly("test-battle-logic");
            assertThat(completedOn.get()).isEqualTo("test-battle-rpc-reply");
        } finally {
            replies.shutdownNow();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    void 回复执行器已停时退回完成线程直接完成() throws Exception {
        admission.open();
        ExecutorService replies = Executors.newSingleThreadExecutor();
        replies.shutdown();
        BattleNodeServiceImpl service = new BattleNodeServiceImpl(admission, logic, rooms, replies, 8, metrics);

        CompletableFuture<CreateBattleResult> future = service.createBattle(create(7));
        logic.runPending();

        assertThat(future.get(1, TimeUnit.SECONDS).getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(service.inFlight()).isZero();
    }

    @Test
    void 在途上限必须为正() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
