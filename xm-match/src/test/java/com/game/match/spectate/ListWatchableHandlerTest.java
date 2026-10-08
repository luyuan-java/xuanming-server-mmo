package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 164 经派发器之后回给 gate 的 {@link ClientReply}（spectate-spec §4.11 给 match-spec §8.1 的 164 一行、每一列）：正常、会话没绑定玩家
 * （照常回列表，BW4）、请求体解析失败、读索引失败、批读落点失败、工作池满、排队超预算。处理器接在真的 {@link MatchDispatcher} 上，
 * 消息号从契约的号表取；存储是内存替身。164 的判定细节在 {@code WatchableListServiceTest}。
 */
class ListWatchableHandlerTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int LIST = REGISTRY.requireId(MatchMethods.SERVICE, MatchMethods.LIST_WATCHABLE_BATTLES);
    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final ByteString GARBAGE = ByteString.copyFrom(new byte[] {(byte) 0xFF});
    private static final Executor REJECTING = task -> {
        throw new RejectedExecutionException("满了");
    };

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore();
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), placements);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final ListWatchableHandler handler = new ListWatchableHandler(new WatchableListService(store, metrics), metrics);
    /** 把交进来的任务记下来再当场跑：既能断言「确实进了工作池」，又保持同步。 */
    private final List<Runnable> submitted = new ArrayList<>();
    private final Executor workers = task -> {
        submitted.add(task);
        task.run();
    };
    private final MatchDispatcher dispatcher = new MatchDispatcher(REGISTRY, List.of(handler), workers, metrics, 4500);

    private void register(long battleId, long createdAtMs, String... names) {
        placements.put(BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-7").setRpcHost("127.0.0.1")
                .setRpcPort(21207).setAttempt(1).setMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE).setBattleConfigId(1).addAllPlayerNames(List.of(names))
                .setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000).build());
        store.putWatchable(battleId, createdAtMs);
    }

    private static ClientCall call(long sessionPlayer, ByteString body) {
        return ClientCall.newBuilder().setMessageId(LIST).setRequestId(9).setBody(body)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(5).setAccount("acc")
                        .setPlayerId(sessionPlayer))
                .build();
    }

    private static ByteString request(int limit) {
        return ListWatchableBattlesRequest.newBuilder().setLimit(limit).build().toByteString();
    }

    private static ClientReply await(CompletableFuture<ClientReply> reply) throws Exception {
        return reply.get(5, TimeUnit.SECONDS);
    }

    private static void assertEnvelope1003(ClientReply reply) {
        assertThat(reply.getTipId()).isEqualTo(1003);
        assertThat(reply.getTipParametersList()).as("信封不带 parameters").isEmpty();
        assertThat(reply.getBody().isEmpty()).as("信封没有应答体").isTrue();
    }

    private double listed(String result) {
        return meters.get("xm.match.list.watchable").tags("result", result).counter().count();
    }

    private double requests(String result) {
        return meters.get("xm.match.requests").tags("method", MatchMethods.LIST_WATCHABLE_BATTLES, "result", result).timer().count();
    }

    @Test
    void 管的是ListWatchableBattles_不当场回_不带自己的执行器_所以跑在共用的工作池上() throws Exception {
        MatchMethodHandler asHandler = handler;
        assertThat(asHandler.method()).isEqualTo(MatchMethods.LIST_WATCHABLE_BATTLES);
        assertThat(asHandler.inline()).as("要读 Redis：不在 Dubbo 线程上当场回").isFalse();
        assertThat(asHandler.executor()).as("只有 Redis 操作：共用 match-worker，不像 163 那样自带执行器").isNull();

        await(dispatcher.dispatch(call(1001, request(0))));

        assertThat(submitted).as("派发器把它交给了工作池").hasSize(1);
    }

    @Test
    void 正常_应答体是列表_最新在前_信封码为0() throws Exception {
        register(880400, T0 - 2_000, "甲");
        register(880401, T0 - 1_000, "乙", "丙");

        ClientReply reply = await(dispatcher.dispatch(call(1001, request(6))));

        assertThat(reply.getTipId()).isZero();
        ListWatchableBattlesResponse response = ListWatchableBattlesResponse.parseFrom(reply.getBody());
        assertThat(response.getBattlesList()).containsExactly(
                BattleWatchSummary.newBuilder().setBattleId(880401).setMode(MatchMode.MATCH_MODE_PVE_SOLO).setBattleConfigId(1).addPlayerNames("乙")
                        .addPlayerNames("丙").setCreatedAtMs(T0 - 1_000).build(),
                BattleWatchSummary.newBuilder().setBattleId(880400).setMode(MatchMode.MATCH_MODE_PVE_SOLO).setBattleConfigId(1).addPlayerNames("甲")
                        .setCreatedAtMs(T0 - 2_000).build());
        assertThat(store.calls).as("条数取请求里的 limit").startsWith("list(6)");
        assertThat(listed("ok")).isEqualTo(1.0);
        assertThat(requests("ok")).isEqualTo(1.0);
    }

    @Test
    void 会话没绑定玩家_照常回列表_请求体里的player_id也不看() throws Exception {
        register(880410, T0, "甲");
        ClientReply bound = await(dispatcher.dispatch(call(1001, request(0))));

        ClientReply notInGame = await(dispatcher.dispatch(call(0, request(0))));
        ClientReply forged = await(dispatcher.dispatch(call(0,
                ListWatchableBattlesRequest.newBuilder().setPlayerId(424242).build().toByteString())));
        ClientReply emptyBody = await(dispatcher.dispatch(call(0, ByteString.EMPTY)));

        assertThat(notInGame.getTipId()).as("BW4：164 不校验身份、不做互斥").isZero();
        assertThat(ListWatchableBattlesResponse.parseFrom(notInGame.getBody()).getBattlesList()).extracting(BattleWatchSummary::getBattleId)
                .containsExactly(880410L);
        assertThat(notInGame).isEqualTo(bound);
        assertThat(forged).isEqualTo(bound);
        assertThat(emptyBody).as("0 字节的请求体 = 全默认值的请求：limit 0 → 默认条数").isEqualTo(bound);
        assertThat(store.calls).as("四次都是只读的列表流程，没有任何身份 / 互斥相关的读").containsOnly("list(20)", "readPlacements([880410])");
        assertThat(listed("ok")).isEqualTo(4.0);
    }

    @Test
    void 空列表_照常回包_应答体0字节_信封码为0() throws Exception {
        ClientReply reply = await(dispatcher.dispatch(call(1001, request(6))));

        assertThat(reply.getTipId()).as("不是错误：gate 按应答类型（不是 Empty）照常回一个空包").isZero();
        assertThat(reply.getBody().isEmpty()).isTrue();
        assertThat(reply).isEqualTo(ClientReply.getDefaultInstance());
        assertThat(listed("ok")).isEqualTo(1.0);
    }

    @Test
    void 请求体解析失败_信封1003_没进流程_不碰存储() throws Exception {
        register(880420, T0, "甲");

        ClientReply reply = await(dispatcher.dispatch(call(1001, GARBAGE)));

        assertEnvelope1003(reply);
        assertThat(store.calls).isEmpty();
        assertThat(requests("bad_request")).isEqualTo(1.0);
        assertThat(listed("ok") + listed("error") + listed("overloaded")).as("没进过 164 的流程：三个出口都不计").isZero();
    }

    @Test
    void 读索引失败_信封1003_不是空列表_出口计error() throws Exception {
        register(880430, T0, "甲");
        store.faults.failNext("list");

        ClientReply reply = await(dispatcher.dispatch(call(1001, request(0))));

        assertEnvelope1003(reply);
        assertThat(listed("error")).isEqualTo(1.0);
        assertThat(requests("failed")).as("处理器回的是信封").isEqualTo(1.0);
    }

    @Test
    void 批读落点失败_不回1003_回变短的列表() throws Exception {
        register(880440, T0, "甲");
        store.faults.failNext("readPlacements");

        ClientReply reply = await(dispatcher.dispatch(call(1001, request(0))));

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody().isEmpty()).as("这一页都读不出来：空列表").isTrue();
        assertThat(listed("ok")).isEqualTo(1.0);
        assertThat(meters.get("xm.match.watchable.anomalies").tags("reason", "record_read_failed").counter().count()).isEqualTo(1.0);
        assertThat(store.watchable()).as("读失败不剔除").containsExactly("880440");
    }

    @Test
    void 工作池满_信封1003_不调处理流程_出口计overloaded() throws Exception {
        register(880450, T0, "甲");
        MatchDispatcher full = new MatchDispatcher(REGISTRY, List.of(handler), REJECTING, metrics, 4500);

        ClientReply reply = await(full.dispatch(call(1001, request(0))));

        assertEnvelope1003(reply);
        assertThat(store.calls).isEmpty();
        assertThat(listed("overloaded")).isEqualTo(1.0);
        assertThat(listed("ok") + listed("error")).isZero();
        assertThat(requests("overloaded")).isEqualTo(1.0);
    }

    @Test
    void 在工作队列里等过了预算_同样按过载回信封1003() throws Exception {
        register(880460, T0, "甲");
        MatchDispatcher exhausted = new MatchDispatcher(REGISTRY, List.of(handler), workers, metrics, 0);

        ClientReply reply = await(exhausted.dispatch(call(1001, request(0))));

        assertEnvelope1003(reply);
        assertThat(store.calls).as("轮到执行时预算已用完：不再去读 Redis").isEmpty();
        assertThat(listed("overloaded")).isEqualTo(1.0);
    }

    @Test
    void 过载应答本身_是不带应答体的信封1003() {
        assertThat(handler.onOverload()).isEqualTo(MatchMethodHandler.Reply.envelope(1003));
        assertThat(listed("overloaded")).as("功能自己的出口计数在 onOverload 里记").isEqualTo(1.0);
    }
}
