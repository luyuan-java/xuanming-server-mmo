package com.game.friend.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup;
import com.game.discovery.presence.PlayerPushes;
import com.game.friend.cache.FriendCache;
import com.game.friend.cache.InMemoryCacheRedis;
import com.game.friend.directory.OnlineDirectory;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.quota.DirectoryQuota;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.service.FriendService;
import com.game.friend.service.RecommendService;
import com.game.friend.store.FriendStore;
import com.game.friend.store.RecommendStore;
import com.game.friend.support.Deadline;
import com.game.proto.friend.AddFriendRequest;
import com.game.proto.friend.AddFriendResponse;
import com.game.proto.friend.GetFriendListResponse;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class FriendDispatcherTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int ADD = REGISTRY.requireId(FriendDispatcher.SERVICE, "AddFriend");
    private static final int LIST = REGISTRY.requireId(FriendDispatcher.SERVICE, "GetFriendList");
    private static final int NOTIFY = REGISTRY.requireId(FriendDispatcher.SERVICE, "NotifyFriendEvent");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FriendMetrics metrics = new FriendMetrics(registry);

    private FriendDispatcher dispatcher(Executor executor, FriendStore.AddResult addResult) {
        FriendStore store = new FriendStore() {
            @Override
            public AddResult addRequest(long from, long to, Deadline deadline) {
                return addResult;
            }

            @Override
            public AcceptResult accept(long from, long me, Deadline deadline) {
                return AcceptResult.OK;
            }

            @Override
            public RejectResult reject(long from, long me, Deadline deadline) {
                return RejectResult.OK;
            }

            @Override
            public RemoveResult remove(long me, long target, Deadline deadline) {
                return RemoveResult.REMOVED;
            }

            @Override
            public BlockResult block(long me, long target, Deadline deadline) {
                return BlockResult.OK;
            }

            @Override
            public void unblock(long me, long target, Deadline deadline) {
            }

            @Override
            public List<FriendEdge> friends(long me, int limit, Deadline deadline) {
                throw new IllegalStateException("boom");
            }

            @Override
            public List<PendingRequest> pendingRequests(long me, int limit, Deadline deadline) {
                return List.of();
            }

            @Override
            public List<BlockEdge> blocks(long me, int limit, Deadline deadline) {
                return List.of();
            }
        };
        FriendService service = new FriendService(store, new FriendCache(new InMemoryCacheRedis(), Duration.ofMinutes(1), metrics),
                new FriendRequestQuota(k -> CompletableFuture.completedFuture(1L), 10, metrics), (ids, d) -> Map.of(),
                ids -> CompletableFuture.completedFuture(new StrictLookup(Map.of(), 0, 0)),
                (to, content) -> CompletableFuture.completedFuture(PlayerPushes.Outcome.OFFLINE),
                metrics, 1000, Duration.ofMillis(100), System::currentTimeMillis, NOTIFY);
        RecommendService recommend = new RecommendService(new RecommendStore(null, 1, (lo, span) -> 0),
                new OnlineDirectory(null, "xm:presence:", (ids, d) -> Map.of()),
                new DirectoryQuota(k -> CompletableFuture.completedFuture(1L), metrics), (ids, d) -> Map.of(),
                ids -> CompletableFuture.completedFuture(new StrictLookup(Map.of(), 0, 0)), metrics, 10, 20, 64);
        return new FriendDispatcher(REGISTRY, service, recommend, executor, metrics, 3500);
    }

    private static ClientCall call(int messageId, long playerId, ByteString body) {
        return ClientCall.newBuilder().setMessageId(messageId).setBody(body)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(9).setPlayerId(playerId)).build();
    }

    private double requests(String method, String result) {
        return registry.get("xm.friend.requests").tag("method", method).tag("result", result).timer().count();
    }

    @Test
    void 接管10个C2S消息号_不含推送的235() {
        FriendDispatcher d = dispatcher(Runnable::run, FriendStore.AddResult.OK);
        assertThat(d.routedMessageIds()).hasSize(10).doesNotContain(NOTIFY);
        assertThat(d.notifyMessageId()).isEqualTo(NOTIFY);
    }

    @Test
    void 上行235与未进游戏的会话回信封1003() {
        FriendDispatcher d = dispatcher(Runnable::run, FriendStore.AddResult.OK);
        assertThat(d.dispatch(call(NOTIFY, 5, ByteString.EMPTY)).join()).isEqualTo(ClientReply.newBuilder().setTipId(1003).build());
        assertThat(d.dispatch(call(ADD, 0, ByteString.EMPTY)).join()).isEqualTo(ClientReply.newBuilder().setTipId(1003).build());
        assertThat(d.dispatch(ClientCall.newBuilder().setMessageId(ADD).build()).join().getTipId()).isEqualTo(1003);
        assertThat(requests("NotifyFriendEvent", "forbidden")).isEqualTo(1);
        assertThat(requests("AddFriend", "unauthenticated")).isEqualTo(2);
    }

    @Test
    void 不认识的消息号_契约里没有回1013_有但不归friend回1006() {
        FriendDispatcher d = dispatcher(Runnable::run, FriendStore.AddResult.OK);
        assertThat(d.dispatch(call(65_000, 5, ByteString.EMPTY)).join().getTipId()).isEqualTo(1013);
        int login = REGISTRY.requireId("ClientPlayerLogin", "Login");
        assertThat(d.dispatch(call(login, 5, ByteString.EMPTY)).join().getTipId()).isEqualTo(1006);
    }

    @Test
    void 请求体解析失败回信封1003() {
        FriendDispatcher d = dispatcher(Runnable::run, FriendStore.AddResult.OK);
        ClientReply reply = d.dispatch(call(ADD, 5, ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01}))).join();
        assertThat(reply.getTipId()).isEqualTo(1003);
        assertThat(reply.getBody()).isEmpty();
        assertThat(requests("AddFriend", "bad_request")).isEqualTo(1);
    }

    @Test
    void 正常应答与业务拒绝_结果分类() throws Exception {
        FriendDispatcher ok = dispatcher(Runnable::run, FriendStore.AddResult.OK);
        ByteString body = AddFriendRequest.newBuilder().setTargetPlayerId(7).build().toByteString();
        ClientReply reply = ok.dispatch(call(ADD, 5, body)).join();
        assertThat(reply.getTipId()).isZero();
        assertThat(AddFriendResponse.parseFrom(reply.getBody()).hasErrorMessage()).isFalse();
        assertThat(requests("AddFriend", "ok")).isEqualTo(1);

        FriendDispatcher rejected = dispatcher(Runnable::run, FriendStore.AddResult.ALREADY_SENT);
        reply = rejected.dispatch(call(ADD, 5, body)).join();
        assertThat(AddFriendResponse.parseFrom(reply.getBody()).getErrorMessage().getId()).isEqualTo(15003);
        assertThat(requests("AddFriend", "business_error")).isEqualTo(1);

        // 依赖故障：应答体里的 1003 计 internal_error
        reply = ok.dispatch(call(LIST, 5, ByteString.EMPTY)).join();
        assertThat(GetFriendListResponse.parseFrom(reply.getBody()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(requests("GetFriendList", "internal_error")).isEqualTo(1);
    }

    @Test
    void 工作队列满回应答体1003() throws Exception {
        FriendDispatcher d = dispatcher(task -> {
            throw new RejectedExecutionException("full");
        }, FriendStore.AddResult.OK);
        ClientReply reply = d.dispatch(call(ADD, 5, ByteString.EMPTY)).join();
        assertThat(reply.getTipId()).isZero();
        assertThat(AddFriendResponse.parseFrom(reply.getBody()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(requests("AddFriend", "overloaded")).isEqualTo(1);
    }
}
