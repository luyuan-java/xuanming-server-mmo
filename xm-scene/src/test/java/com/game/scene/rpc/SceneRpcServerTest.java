package com.game.scene.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.DubboGroups;
import com.game.api.SceneBattleService;
import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.api.rpc.NodeRpcClients;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpEndpoint;
import com.game.scene.asset.AssetOpService;
import com.game.scene.asset.SceneAssetOpProvider;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.SceneBattleProvider;
import com.game.scene.player.Wallet;
import com.game.scene.testing.FakeBattleLocks;
import com.game.scene.world.ScenePlayer;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * scene 节点的 Dubbo 导出（scene-battle-spec §7.3「导出」；§13.2 SceneRpcServerTest 与 §13.7 跨进程的进程内真 Triple 回环）：
 * <b>一个端口、一个协议上导出两个服务</b>——资产通道 {@code SceneAssetOpService}（group {@code scene-asset}）与回合制战斗入口
 * {@code SceneBattleService}（group {@code scene-battle}），都是 {@code register = false}。提供方与调用方各在自己的 Dubbo 框架模型里
 * （等价于两个进程），调用方就是 battle 用的 {@link NodeRpcClients}{@code <SceneBattleService>} 与帮会用的 {@link SceneAssetOpClients}，
 * 调用方鉴权过滤器照常生效（测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）。对面是<b>真的</b> {@link SceneBattleProvider} + 真的
 * {@code PlayerBattleService}（{@link BattleFixture}）。
 *
 * <p>线程：两个提供方把任务投进一条线程安全的队列，由测试线程取出来跑（{@link #await}）——测试线程就是夹具的逻辑线程；
 * 回写线程池的线程名同生产（{@code scene-asset-reply}）。
 */
class SceneRpcServerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String REPLY_THREAD = "scene-asset-reply";
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long GOLD = 100;
    private static final String SECRET = "scene-rpc-server-test-secret-0123456789";

    private final BattleFixture f = new BattleFixture();
    /** 提供方投递的「逻辑线程」任务（Dubbo 线程入队，测试线程出队执行）。 */
    private final LinkedBlockingQueue<Runnable> logicQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger replyThreads = new AtomicInteger();
    private final ExecutorService replyPool = Executors.newFixedThreadPool(2,
            r -> new Thread(r, REPLY_THREAD + "-" + replyThreads.incrementAndGet()));
    /** 战斗提供方每条应答是在哪条线程上完成的（没进逻辑线程、当场完成的记 {@code "(inline)"}）。 */
    private final List<String> battleRepliesCompletedOn = Collections.synchronizedList(new ArrayList<>());
    /** 「挂上完成回调」与「逻辑线程跑任务」互斥：任务一定在回调挂好之后才跑，完成线程因此是确定的。 */
    private final Object gate = new Object();

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private SceneRpcServer server;
    private SceneBattleProvider battleProvider;
    private NodeRpcClients<SceneBattleService> battleClients;
    private SceneAssetOpClients assetClients;
    private int port;

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        battleClients = new NodeRpcClients<>("xm-scene-test-battle-caller", SceneBattleService.class, DubboGroups.SCENE_BATTLE, TIMEOUT,
                "test-scene-battle-connect");
        assetClients = new SceneAssetOpClients("xm-scene-test-asset-caller");
    }

    @AfterEach
    void tearDown() throws Exception {
        battleClients.close();
        assetClients.close();
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        if (server != null) {
            server.close();
        }
        replyPool.shutdownNow();
    }

    // ================================================================== 导出

    @Test
    void 一个端口导出两个服务_两个group_都不进注册中心() {
        export(8);

        assertThat(server.port()).isEqualTo(port);
        List<String> urls = server.exportedUrls();
        assertThat(urls).as("恰好两条：资产通道与战斗入口").hasSize(2);
        assertThat(urls).allSatisfy(url -> assertThat(url).startsWith("tri://").contains(":" + port + "/").contains("register=false"));
        assertThat(urls).filteredOn(url -> url.contains("/com.game.api.SceneAssetOpService")).singleElement()
                .satisfies(url -> assertThat(url).contains("group=scene-asset").doesNotContain("group=scene-battle"));
        assertThat(urls).filteredOn(url -> url.contains("/com.game.api.SceneBattleService")).singleElement()
                .satisfies(url -> assertThat(url).contains("group=scene-battle").doesNotContain("group=scene-asset"));
        assertThat(urls).as("同一个 Dubbo 应用").allSatisfy(url -> assertThat(url).contains("application=" + SceneRpcServer.APPLICATION));
    }

    /**
     * 同一个端口上，两个 group 的调用各自到达自己的服务，互不串：帮会的资产扣款到 {@code AssetOpService}，battle 的备战 / 确认 / 结算到
     * {@code PlayerBattleService}。两条通道作用在同一名玩家上——战斗在途时资产通道回 RETRY 27002，结算解冻后同一个 seq 照常扣。
     */
    @Test
    void 同一端口上两个group各自调通_战斗入口四个方法与资产通道作用在同一名玩家上() throws Exception {
        export(8);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.currency.add(player, Wallet.GOLD, 1000, Reason.GM_GRANT).ok()).isTrue();

        assertThat(await(asset(AssetRpc.DEBIT, debit(1, 30))).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(f.gold(player)).isEqualTo(970);

        SceneBattleReply prepared = await(battle(s -> s.prepareBattle(call(f.prepareRequest(PLAYER, X).toByteArray()))));
        assertThat(prepared.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        PrepareBattleResponse response = PrepareBattleResponse.parseFrom(prepared.getBody());
        assertThat(response.hasErrorMessage()).as("契约消息按字节嵌入，原样到达调用方").isFalse();
        assertThat(response.getSnapshot().getPlayerId()).isEqualTo(PLAYER);
        assertThat(response.getTableFingerprint()).isEqualTo(f.tables.fingerprint());
        assertThat(player.inBattle()).isTrue();

        AssetOpResponse gated = await(asset(AssetRpc.DEBIT, debit(2, 30)));
        assertThat(gated.getOutcome()).as("战斗在途：资产通道 RETRY").isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(gated.getReason()).isEqualTo(27002);

        long deadline = f.deadline();
        SceneBattleReply confirmed = await(battle(s -> s.confirmBattle(call(BattleConfirmedEvent.newBuilder().setPlayerId(PLAYER)
                .setBattleId(X).setDeadlineMs(deadline).build().toByteArray()))));
        assertThat(confirmed.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(BattleFixture.freeze(player).phase()).isEqualTo(com.game.scene.battle.BattleFreeze.Phase.FIGHTING);

        SceneBattleReply cancelled = await(battle(s -> s.cancelBattlePrepare(call(com.game.proto.CancelBattlePrepareRequest.newBuilder()
                .setPlayerId(PLAYER).setBattleId(X).build().toByteArray()))));
        assertThat(cancelled.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(player.inBattle()).as("已确认开战：取消被拒").isTrue();

        SceneBattleReply settled = await(battle(s -> s.applySettlement(call(settlementBody()))));
        assertThat(settled.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(settled.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.gold(player)).isEqualTo(970 + GOLD);

        assertThat(await(asset(AssetRpc.DEBIT, debit(2, 30))).getOutcome()).as("解冻后同一个 seq 照常扣")
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(f.gold(player)).isEqualTo(940 + GOLD);
        assertThat(battleClients.size()).as("同地址只建一个战斗客户端").isEqualTo(1);
    }

    // ================================================================== §13.7 跨进程：battle 的 NodeRpcClients → scene 提供方

    /** 调用方手里的实例号不是本进程（scene 重启过）：经 Triple 回一条 NOT_HERE 应答，没进逻辑线程。 */
    @Test
    void 实例不符_经Triple回NOT_HERE_没进逻辑线程() throws Exception {
        export(8);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);

        SceneBattleReply reply = battle(s -> s.applySettlement(SceneBattleCall.newBuilder().setTargetInstanceId("scene-instance-old")
                .setPlayerId(PLAYER).setBody(ByteString.copyFrom(settlementBody())).build())).get(10, TimeUnit.SECONDS);

        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(reply.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED);
        assertThat(logicQueue).as("没进逻辑线程").isEmpty();
        assertThat(f.gold(player)).isZero();
        assertThat(player.inBattle()).isTrue();
        assertThat(battleProvider.inFlight()).isZero();
        assertThat(rpc("settlement", "not_here")).isEqualTo(1);
    }

    /** 不带调用方 MAC 的直连调用（绕开了 xm-api 的调用方过滤器）：被提供方鉴权过滤器拒绝，根本到不了提供方。 */
    @Test
    void 不带调用方MAC_鉴权拒绝_没进提供方() throws Exception {
        export(8);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        IsolatedDubboModule anonymousModel = IsolatedDubboModule.create("xm-scene-test-anonymous");
        closeables.add(anonymousModel);
        ReferenceConfig<SceneBattleService> reference = new ReferenceConfig<>(anonymousModel.module());
        reference.setInterface(SceneBattleService.class);
        reference.setGroup(DubboGroups.SCENE_BATTLE);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout((int) TIMEOUT.toMillis());
        reference.setFilter("-xmAuthConsumer");
        SceneBattleService anonymous = reference.get();
        SceneBattleCall wrongInstance = SceneBattleCall.newBuilder().setTargetInstanceId("other").setPlayerId(PLAYER).build();

        assertAuthRejected(() -> anonymous.applySettlement(call(settlementBody())).get(10, TimeUnit.SECONDS));
        assertAuthRejected(() -> anonymous.prepareBattle(call(f.prepareRequest(PLAYER, 8).toByteArray())).get(10, TimeUnit.SECONDS));
        // 连不进逻辑线程的调用（实例不符）也到不了提供方
        assertAuthRejected(() -> anonymous.confirmBattle(wrongInstance).get(10, TimeUnit.SECONDS));
        assertAuthRejected(() -> anonymous.cancelBattlePrepare(wrongInstance).get(10, TimeUnit.SECONDS));

        assertThat(logicQueue).as("没进逻辑线程").isEmpty();
        assertThat(battleRepliesCompletedOn).as("连提供方都没进").isEmpty();
        assertThat(battleProvider.inFlight()).isZero();
        for (String result : List.of("handled", "not_here", "deferred", "overloaded", "error")) {
            assertThat(rpc("settlement", result) + rpc("prepare", result) + rpc("confirm", result)).as(result).isZero();
        }
        assertThat(f.gold(player)).isZero();
        assertThat(player.inBattle()).isTrue();

        // 对照：同一个服务、同一条调用，带着调用方 MAC 就到得了提供方——上面被拒的原因只能是鉴权
        assertThat(battle(s -> s.confirmBattle(wrongInstance)).get(10, TimeUnit.SECONDS).getStatus())
                .isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(battleRepliesCompletedOn).containsExactly("(inline)");
        assertThat(rpc("confirm", "not_here")).isEqualTo(1);
    }

    /**
     * 在途超限：第二条调用经 Triple 得到一条 <b>OVERLOADED 应答</b>（保证没进逻辑线程、零副作用；资产通道的过载则是传输失败，两者不同）。
     * 第一条等逻辑线程跑完照常 HANDLED。
     */
    @Test
    void 在途超限_经Triple回OVERLOADED应答_第一条照常完成() throws Exception {
        export(1);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        CompletableFuture<SceneBattleReply> holding = battle(s -> s.applySettlement(call(settlementBody())));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (logicQueue.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(logicQueue).as("第一条已占住唯一的名额、停在逻辑队列里").hasSize(1);

        SceneBattleReply overloaded = battle(s -> s.applySettlement(call(settlementBody()))).get(10, TimeUnit.SECONDS);

        assertThat(overloaded.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_OVERLOADED);
        assertThat(logicQueue).as("过载的那条没进逻辑线程").hasSize(1);
        assertThat(f.gold(player)).isZero();
        assertThat(rpc("settlement", "overloaded")).isEqualTo(1);

        SceneBattleReply first = await(holding);
        assertThat(first.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(battleProvider.inFlight()).isZero();
    }

    /** 进了逻辑线程的应答在回写线程池（{@code scene-asset-reply}）上完成：Dubbo 的序列化与回写不占逻辑线程。 */
    @Test
    void 进了逻辑线程的应答在scene_asset_reply线程上完成() throws Exception {
        export(8);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        String logicThreadName = Thread.currentThread().getName();

        SceneBattleReply settled = await(battle(s -> s.applySettlement(call(settlementBody()))));
        SceneBattleReply again = await(battle(s -> s.applySettlement(call(settlementBody()))));
        SceneBattleReply wrongInstance = battle(s -> s.confirmBattle(SceneBattleCall.newBuilder().setTargetInstanceId("other")
                .setPlayerId(PLAYER).build())).get(10, TimeUnit.SECONDS);

        assertThat(settled.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(again.getSettlement()).as("重投命中账本").isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(wrongInstance.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(battleRepliesCompletedOn).hasSize(3);
        assertThat(battleRepliesCompletedOn.subList(0, 2)).as("进了逻辑线程的两条").allSatisfy(thread -> assertThat(thread)
                .startsWith(REPLY_THREAD).isNotEqualTo(logicThreadName));
        assertThat(battleRepliesCompletedOn.get(2)).as("没进逻辑线程的当场回").isEqualTo("(inline)");
    }

    // ================================================================== 端口与关闭

    @Test
    void 端口被占_导出失败_端口号非法直接拒绝() throws IOException {
        try (ServerSocket busy = new ServerSocket(0)) {
            int taken = busy.getLocalPort();
            assertThatThrownBy(() -> SceneRpcServer.export(assetProvider(), newBattleProvider(8), "127.0.0.1", taken))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(Integer.toString(taken));
        }
        assertThatThrownBy(() -> SceneRpcServer.export(assetProvider(), newBattleProvider(8), "127.0.0.1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneRpcServer.export(assetProvider(), newBattleProvider(8), "127.0.0.1", 65536))
                .isInstanceOf(IllegalArgumentException.class);
        // 失败的导出把自己的 Dubbo 模型收拾干净了：随后在空闲端口上照常导出两个服务
        export(8);
        assertThat(server.exportedUrls()).hasSize(2);
    }

    @Test
    void 关闭幂等_关闭后两个服务都连不上_端口已释放() throws Exception {
        export(8);
        f.enter(SESSION, PLAYER);
        assertThat(battle(s -> s.confirmBattle(SceneBattleCall.newBuilder().setTargetInstanceId("other").build())).get(10, TimeUnit.SECONDS)
                .getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);

        server.close();
        server.close();
        server = null;

        CompletableFuture<SceneBattleReply> battleAfter = battleClients.call(target(), Duration.ofMillis(800),
                s -> s.confirmBattle(SceneBattleCall.newBuilder().setTargetInstanceId("other").build()));
        assertThatThrownBy(() -> battleAfter.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        CompletableFuture<AssetOpResponse> assetAfter = assetClients.call(endpoint(), AssetRpc.DEBIT, signed(AssetRpc.DEBIT, debit(1, 1)),
                Duration.ofMillis(800));
        assertThatThrownBy(() -> assetAfter.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        try (ServerSocket rebound = new ServerSocket(port)) {
            assertThat(rebound.isBound()).as("端口已释放").isTrue();
        }
    }

    // ================================================================== 工具

    /**
     * 这次调用是被提供方的<b>鉴权过滤器</b>拒的（不是连不上、超时或别的传输失败）：异常完成，原因是 Triple 状态 {@code UNAUTHENTICATED}
     * （提供方过滤器抛的 {@code RpcException(AUTHORIZATION_EXCEPTION)} 在线上的形态）。过滤器给的那句中文原因经 grpc-message 头传过来只剩问号，
     * 所以按状态码认，不按文案认。
     */
    private static void assertAuthRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(org.apache.dubbo.rpc.StatusRpcException.class)
                .satisfies(error -> assertThat(((org.apache.dubbo.rpc.StatusRpcException) error).getStatus().code)
                        .isEqualTo(org.apache.dubbo.rpc.TriRpcStatus.Code.UNAUTHENTICATED));
    }

    private void export(int battleMaxInFlight) {
        battleProvider = newBattleProvider(battleMaxInFlight);
        server = SceneRpcServer.export(assetProvider(), new Recording(battleProvider), "127.0.0.1", port);
    }

    private SceneBattleProvider newBattleProvider(int maxInFlight) {
        return new SceneBattleProvider(() -> f.battle, BattleFixture.SCENE_INSTANCE, logicQueue::add, maxInFlight, replyPool,
                f.battleMetrics);
    }

    private SceneAssetOpProvider assetProvider() {
        AssetOpService assetOps = new AssetOpService(f.world, f.currency, f.bags, new AssetOpAuth(caller -> SECRET), f.clock,
                f.sceneMetrics);
        AssetOpEndpoint endpoint = new AssetOpEndpoint(logicQueue::add, assetOps);
        return new SceneAssetOpProvider(() -> endpoint, 8, replyPool, f.sceneMetrics);
    }

    /** 转手给真提供方，并记下每条应答是在哪条线程上完成的。 */
    private final class Recording implements SceneBattleService {

        private final SceneBattleService delegate;

        Recording(SceneBattleService delegate) {
            this.delegate = delegate;
        }

        private CompletableFuture<SceneBattleReply> watch(java.util.function.Supplier<CompletableFuture<SceneBattleReply>> invocation) {
            synchronized (gate) {
                CompletableFuture<SceneBattleReply> reply = invocation.get();
                boolean inline = reply.isDone();
                reply.whenComplete((r, e) -> battleRepliesCompletedOn.add(inline ? "(inline)" : Thread.currentThread().getName()));
                return reply;
            }
        }

        @Override
        public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
            return watch(() -> delegate.prepareBattle(call));
        }

        @Override
        public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
            return watch(() -> delegate.cancelBattlePrepare(call));
        }

        @Override
        public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
            return watch(() -> delegate.confirmBattle(call));
        }

        @Override
        public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
            return watch(() -> delegate.applySettlement(call));
        }
    }

    /** 在测试线程（= 夹具的逻辑线程）上跑提供方投来的任务，直到这条调用有了结局。 */
    private <T> T await(CompletableFuture<T> reply) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!reply.isDone()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("15 s 内没有结局；逻辑队列=" + logicQueue.size() + " 夹具逻辑队列=" + f.logic.pending());
            }
            Runnable task = logicQueue.poll(10, TimeUnit.MILLISECONDS);
            if (task != null) {
                synchronized (gate) {
                    task.run();
                    f.drain();
                }
            }
        }
        return reply.get();
    }

    private NodeRpcClients.Target target() {
        return new NodeRpcClients.Target("127.0.0.1", port, BattleFixture.SCENE_INSTANCE);
    }

    private SceneAssetEndpoint endpoint() {
        return new SceneAssetEndpoint(BattleFixture.ZONE, BattleFixture.LOCAL_NODE, BattleFixture.SCENE_INSTANCE, "127.0.0.1", port);
    }

    private CompletableFuture<SceneBattleReply> battle(
            java.util.function.Function<SceneBattleService, CompletableFuture<SceneBattleReply>> invocation) {
        return battleClients.call(target(), TIMEOUT, invocation);
    }

    private CompletableFuture<AssetOpResponse> asset(AssetRpc rpc, AssetOpRequest request) {
        return assetClients.call(endpoint(), rpc, signed(rpc, request), TIMEOUT);
    }

    private static SceneBattleCall call(byte[] body) {
        return SceneBattleCall.newBuilder().setTargetInstanceId(BattleFixture.SCENE_INSTANCE).setPlayerId(PLAYER)
                .setBody(ByteString.copyFrom(body)).build();
    }

    private static byte[] settlementBody() {
        return FakeBattleLocks.record(BattleFixture.settlement(PLAYER, X, GOLD).build());
    }

    private static AssetOpRequest debit(long seq, long amount) {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT).setSeq(seq)
                .setStreamEpoch(1_700_000_000_000L).setTxType(24).setCorrelationId(500 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(amount)))
                .build();
    }

    private AssetOpRequest signed(AssetRpc rpc, AssetOpRequest request) {
        return AssetOpSignatures.sign(rpc, request, AssetOpSignatures.CALLER_GUILD, SECRET, f.clock.epochMillis());
    }

    private double rpc(String method, String result) {
        return f.count("xm.scene.battle.rpc", "method", method, "result", result);
    }
}
