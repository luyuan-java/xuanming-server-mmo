package com.game.friend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.PlayerPresence;
import com.game.friend.cache.FriendCache;
import com.game.friend.cache.InMemoryCacheRedis;
import com.game.friend.metrics.FriendMetrics;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.store.FriendStore;
import com.game.friend.store.FriendStoreException;
import com.game.common.deadline.Deadline;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.AcceptFriendRequest;
import com.game.proto.friend.AddFriendRequest;
import com.game.proto.friend.BlockRequest;
import com.game.proto.friend.FriendEntry;
import com.game.proto.friend.FriendEventReason;
import com.game.proto.friend.FriendEventS2C;
import com.game.proto.friend.FriendRequestStatus;
import com.game.proto.friend.GetFriendListRequest;
import com.game.proto.friend.GetPendingRequestsRequest;
import com.game.proto.friend.ListBlocksRequest;
import com.game.proto.friend.RejectFriendRequest;
import com.game.proto.friend.RemoveFriendRequest;
import com.game.proto.friend.UnblockRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FriendServiceTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private static final long ME = 100;
    private static final SessionContext SESSION = SessionContext.newBuilder().setPlayerId(ME)
            .setAccount("original").setOwnerRevision(7).build();
    private static final int NOTIFY_ID = 235;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FriendMetrics metrics = new FriendMetrics(registry);
    private final StubStore store = new StubStore();
    private final InMemoryCacheRedis cacheRedis = new InMemoryCacheRedis();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger quotaCount = new AtomicInteger();
    private final Map<Long, PlayerPresence> online = new HashMap<>();
    private final Map<Long, Profile> profiles = new HashMap<>();
    private final List<MessageContent> pushed = new CopyOnWriteArrayList<>();
    private final List<Long> pushedTo = new CopyOnWriteArrayList<>();
    private int onlineErrors;
    private int onlineCalls;
    private int quotaLimit = 10;

    private FriendService service() {
        FriendCache cache = new FriendCache(new RecordingRedis(), REDIS_KEYS, Duration.ofMinutes(30), metrics);
        FriendRequestQuota quota = new FriendRequestQuota(
                (key, timeout) -> CompletableFuture.completedFuture((long) quotaCount.incrementAndGet()), REDIS_KEYS, quotaLimit, metrics);
        return new FriendService(store, cache, REDIS_KEYS, quota, (ids, deadline) -> {
            Map<Long, Profile> out = new HashMap<>();
            ids.forEach(id -> {
                if (profiles.containsKey(id)) {
                    out.put(id, profiles.get(id));
                }
            });
            return out;
        }, ids -> {
            onlineCalls++;
            Map<Long, PlayerPresence> found = new HashMap<>();
            ids.forEach(id -> {
                if (online.containsKey(id)) {
                    found.put(id, online.get(id));
                }
            });
            return CompletableFuture.completedFuture(new StrictLookup(found, ids.size() - found.size() - onlineErrors, onlineErrors));
        }, (to, content) -> {
            events.add("push:" + to);
            pushedTo.add(to);
            pushed.add(content);
            return CompletableFuture.completedFuture(PlayerPushes.Outcome.SENT);
        }, metrics, 1000, Duration.ofMillis(1500), () -> 123_456L, NOTIFY_ID);
    }

    /** 记录失效顺序的缓存 Redis（验证「失效完成之后才推送」）。 */
    private final class RecordingRedis implements FriendCache.CacheRedis {
        @Override
        public java.util.concurrent.CompletionStage<String> get(String key) {
            return cacheRedis.get(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<String> generation(String g, String candidate, long t) {
            return cacheRedis.generation(g, candidate, t);
        }

        @Override
        public java.util.concurrent.CompletionStage<Boolean> fill(String g, String d, String e, String p, long t) {
            return cacheRedis.fill(g, d, e, p, t);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> invalidate(String g, String d, String n, long t) {
            events.add("invalidate:" + d);
            return cacheRedis.invalidate(g, d, n, t);
        }
    }

    private static Deadline deadline() {
        return Deadline.after(2000);
    }

    // ================================================================ AddFriend

    @Test
    void 发申请_入口校验顺序_目标为零与加自己不消耗配额() {
        FriendService s = service();
        assertThat(s.addFriend(SESSION, AddFriendRequest.newBuilder().build(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "target_player_id required"));
        assertThat(s.addFriend(SESSION, AddFriendRequest.newBuilder().setTargetPlayerId(ME).build(), deadline())
                .getErrorMessage()).isEqualTo(tip(15000, "cannot add yourself"));
        assertThat(quotaCount).hasValue(0);
        assertThat(store.calls).isEmpty();
    }

    @Test
    void 发申请_超配额1008_不碰存储_被拒的尝试也计数() {
        quotaLimit = 1;
        FriendService s = service();
        store.add = FriendStore.AddResult.BLOCKED;
        assertThat(s.addFriend(SESSION, add(200), deadline()).getErrorMessage().getId()).isEqualTo(15007);
        assertThat(s.addFriend(SESSION, add(200), deadline()).getErrorMessage())
                .isEqualTo(tip(1008, "too many friend requests, retry later"));
        assertThat(store.calls).hasSize(1);
    }

    @Test
    void 发申请成功_只失效对方收件箱_失效之后才推送_推送内容() throws Exception {
        FriendService s = service();
        assertThat(s.addFriend(SESSION, add(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).containsExactly("invalidate:" + REDIS_KEYS.friendPending(200), "push:200");
        MessageContent content = pushed.get(0);
        assertThat(content.getMessageId()).isEqualTo(NOTIFY_ID);
        assertThat(content.getId()).isZero();
        FriendEventS2C event = FriendEventS2C.parseFrom(content.getSerializedMessage());
        assertThat(event.getReason()).isEqualTo(FriendEventReason.FRIEND_EVENT_REASON_REQUEST_RECEIVED);
        assertThat(event.getByPlayerId()).isEqualTo(ME);
        assertThat(event.getTsMs()).isEqualTo(123_456L);
        assertThat(registry.get("game.friend.pushes").tag("reason", "request_received").tag("outcome", "ok").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 发申请_拒绝码映射_满的两个角色与同意相反() {
        FriendService s = service();
        Map<FriendStore.AddResult, Integer> expected = Map.of(
                FriendStore.AddResult.BLOCKED, 15007, FriendStore.AddResult.ALREADY_FRIENDS, 15001,
                FriendStore.AddResult.TOO_MANY_PENDING, 15006,
                FriendStore.AddResult.TARGET_INBOX_FULL, 15009, FriendStore.AddResult.SENDER_FULL, 15002,
                FriendStore.AddResult.RECEIVER_FULL, 15004);
        expected.forEach((result, code) -> {
            store.add = result;
            assertThat(s.addFriend(SESSION, add(200), deadline()).getErrorMessage().getId()).as(result.name()).isEqualTo(code);
        });
        assertThat(events).isEmpty(); // 被拒不失效、不推送
    }

    @Test
    void 存储故障是带固定文案的1003() {
        FriendService s = service();
        store.fail = true;
        assertThat(s.addFriend(SESSION, add(200), deadline()).getErrorMessage()).isEqualTo(tip(1003, "storage unavailable"));
        assertThat(s.acceptFriend(SESSION, accept(200), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(s.block(SESSION, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(s.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline()).getErrorMessage().getId())
                .isEqualTo(1003);
        assertThat(events).isEmpty();
    }

    // ================================================================ AcceptFriend / Reject / Remove

    @Test
    void 同意_入口校验_拒绝码映射_成功失效四个键再推给原申请人() throws Exception {
        FriendService s = service();
        assertThat(s.acceptFriend(SESSION, AcceptFriendRequest.getDefaultInstance(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "request_id required"));
        store.changed = false;
        assertThat(s.acceptFriend(SESSION, accept(ME), deadline()).hasErrorMessage()).isFalse();
        store.changed = true;
        Map<FriendStore.AcceptResult, Integer> expected = Map.of(FriendStore.AcceptResult.NO_PENDING, 15005,
                FriendStore.AcceptResult.BLOCKED, 15007, FriendStore.AcceptResult.SENDER_FULL, 15004,
                FriendStore.AcceptResult.ACCEPTOR_FULL, 15002);
        expected.forEach((result, code) -> {
            store.accept = result;
            assertThat(s.acceptFriend(SESSION, accept(200), deadline()).getErrorMessage().getId()).as(result.name()).isEqualTo(code);
        });
        store.accept = FriendStore.AcceptResult.OK;
        assertThat(s.acceptFriend(SESSION, accept(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(store.calls).last().isEqualTo("accept:123->100");
        assertThat(events).containsExactly("invalidate:" + REDIS_KEYS.friendList(200), "invalidate:" + REDIS_KEYS.friendList(ME),
                "invalidate:" + REDIS_KEYS.friendPending(ME), "invalidate:" + REDIS_KEYS.friendPending(200), "push:200");
        assertThat(FriendEventS2C.parseFrom(pushed.get(0).getSerializedMessage()).getReason())
                .isEqualTo(FriendEventReason.FRIEND_EVENT_REASON_REQUEST_ACCEPTED);
    }

    @Test
    void 拒绝_无申请15005不失效_成功只失效自己收件箱不推送() {
        FriendService s = service();
        store.changed = false;
        assertThat(s.rejectFriend(SESSION, reject(ME), deadline()).hasErrorMessage()).isFalse();
        store.changed = true;
        store.reject = FriendStore.RejectResult.NO_PENDING;
        assertThat(s.rejectFriend(SESSION, reject(200), deadline()).getErrorMessage())
                .isEqualTo(tip(15005, "no pending friend request"));
        assertThat(events).isEmpty();
        store.reject = FriendStore.RejectResult.OK;
        assertThat(s.rejectFriend(SESSION, reject(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).containsExactly("invalidate:" + REDIS_KEYS.friendPending(ME));
    }

    @Test
    void 删除_删自己不做IO_不是好友成功且不失效_删到才失效双方列表() {
        FriendService s = service();
        assertThat(s.removeFriend(SESSION, RemoveFriendRequest.getDefaultInstance(), deadline()).getErrorMessage().getId())
                .isEqualTo(1005);
        assertThat(s.removeFriend(SESSION, remove(ME), deadline()).hasErrorMessage()).isFalse();
        assertThat(store.calls).isEmpty();
        store.remove = FriendStore.RemoveResult.NOT_FRIENDS;
        assertThat(s.removeFriend(SESSION, remove(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).isEmpty();
        store.remove = FriendStore.RemoveResult.REMOVED;
        assertThat(s.removeFriend(SESSION, remove(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).containsExactly("invalidate:" + REDIS_KEYS.friendList(ME), "invalidate:" + REDIS_KEYS.friendList(200));
    }

    // ================================================================ Block / Unblock

    @Test
    void 拉黑_目标非法1005_满15008_成功失效四个键不推送_解除不失效() {
        FriendService s = service();
        assertThat(s.block(SESSION, BlockRequest.newBuilder().setTargetPlayerId(ME).build(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "target_player_id invalid"));
        store.block = FriendStore.BlockResult.BLOCK_LIST_FULL;
        assertThat(s.block(SESSION, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline()).getErrorMessage())
                .isEqualTo(tip(15008, "block list full"));
        store.block = FriendStore.BlockResult.OK;
        assertThat(s.block(SESSION, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline()).hasErrorMessage())
                .isFalse();
        assertThat(events).containsExactly("invalidate:" + REDIS_KEYS.friendList(ME), "invalidate:" + REDIS_KEYS.friendList(200),
                "invalidate:" + REDIS_KEYS.friendPending(ME), "invalidate:" + REDIS_KEYS.friendPending(200));
        events.clear();
        assertThat(s.unblock(SESSION, UnblockRequest.newBuilder().setTargetPlayerId(0).build(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "target_player_id invalid"));
        assertThat(s.unblock(SESSION, UnblockRequest.newBuilder().setTargetPlayerId(200).build(), deadline()).hasErrorMessage())
                .isFalse();
        assertThat(events).isEmpty();
    }

    // ================================================================ 读

    @Test
    void 好友列表_权威关系每次读SQL_在线与资料另行补全() {
        store.friends = List.of(new FriendStore.FriendEdge(200, 10), new FriendStore.FriendEdge(300, 20));
        online.put(200L, PlayerPresence.newBuilder().setPlayerId(200).setOnlineSinceMs(999).build());
        profiles.put(200L, new Profile(200, "alice", 12, 3, 1, "ap", 7));
        FriendService s = service();
        List<FriendEntry> friends = s.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline()).getFriendsList();
        assertThat(friends).hasSize(2);
        assertThat(friends.get(0)).isEqualTo(FriendEntry.newBuilder().setFriendPlayerId(200).setSinceMs(10).setIsOnline(true)
                .setLastActiveMs(999).setName("alice").setLevel(12).setClassId(3).setGender(1).setAppearanceId("ap")
                .setZoneId(7).build());
        assertThat(friends.get(1)).isEqualTo(FriendEntry.newBuilder().setFriendPlayerId(300).setSinceMs(20).build());
        s.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline());
        assertThat(store.calls).containsExactly("friends:100", "friends:100");
        assertThat(registry.get("game.friend.online.lookups").tag("outcome", "ok").counter().count()).isEqualTo(2);
        assertThat(registry.get("game.friend.online.lookups").tag("outcome", "offline").counter().count()).isEqualTo(2);
    }

    @Test
    void 好友列表_在线状态有一个读失败就整体1003_空列表不读在线() {
        FriendService s = service();
        store.friends = List.of();
        assertThat(s.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline()).getFriendsList()).isEmpty();
        assertThat(onlineCalls).isZero();

        store.friends = List.of(new FriendStore.FriendEdge(200, 10), new FriendStore.FriendEdge(300, 20));
        cacheRedis.values.clear();
        online.put(200L, PlayerPresence.newBuilder().setPlayerId(200).build());
        onlineErrors = 1;
        var response = s.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline());
        assertThat(response.getErrorMessage()).isEqualTo(tip(1003, "storage unavailable"));
        assertThat(response.getFriendsList()).isEmpty();
    }

    @Test
    void 入站申请与黑名单列表() {
        store.pending = List.of(new FriendStore.PendingRequest(200, ME, 55, 1, 123));
        store.blocks = List.of(new FriendStore.BlockEdge(300, 66));
        FriendService s = service();
        var pending = s.getPendingRequests(SESSION, GetPendingRequestsRequest.getDefaultInstance(), deadline()).getRequests(0);
        assertThat(pending.getFromPlayerId()).isEqualTo(200);
        assertThat(pending.getToPlayerId()).isEqualTo(ME);
        assertThat(pending.getRequestTimeMs()).isEqualTo(55);
        assertThat(pending.getRequestId()).isEqualTo(123);
        assertThat(pending.getStatus()).isEqualTo(FriendRequestStatus.FRIEND_REQUEST_PENDING);
        var blocks = s.listBlocks(SESSION, ListBlocksRequest.getDefaultInstance(), deadline()).getBlocksList();
        assertThat(blocks).singleElement().satisfies(b -> {
            assertThat(b.getBlockedPlayerId()).isEqualTo(300);
            assertThat(b.getSinceMs()).isEqualTo(66);
        });
        assertThat(onlineCalls).isZero(); // 黑名单刻意不带在线状态
    }

    @Test
    void 重复申请与重复终结不重复推送或失效_仍回原申请ID() {
        FriendService service = service();
        store.created = false;
        var replay = service.addFriend(SESSION, add(200), deadline());
        assertThat(replay.hasErrorMessage()).isFalse();
        assertThat(replay.getRequestId()).isEqualTo(123);
        store.changed = false;
        assertThat(service.acceptFriend(SESSION, accept(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(service.rejectFriend(SESSION, reject(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void 只有申请ID也能处理_零ID不能由from字段绕过() {
        FriendService service = service();
        assertThat(service.acceptFriend(SESSION, AcceptFriendRequest.newBuilder().setFromPlayerId(200).build(), deadline())
                .getErrorMessage().getId()).isEqualTo(1005);
        assertThat(service.rejectFriend(SESSION, RejectFriendRequest.newBuilder().setFromPlayerId(200).build(), deadline())
                .getErrorMessage().getId()).isEqualTo(1005);
        assertThat(store.calls).isEmpty();
        assertThat(service.acceptFriend(SESSION, AcceptFriendRequest.newBuilder().setRequestId(123).build(), deadline())
                .hasErrorMessage()).isFalse();
        assertThat(pushedTo).containsExactly(200L);
    }

    @Test
    void 旧缓存不能作为好友关系与申请权威返回_SQL失败就拒绝() {
        cacheRedis.values.put(REDIS_KEYS.friendList(ME), "[{\"friend_player_id\":200,\"since_ms\":1}]");
        cacheRedis.values.put(REDIS_KEYS.friendPending(ME), "[{\"from_player_id\":200,\"to_player_id\":100,\"request_time_ms\":1,\"status\":1}]");
        FriendService service = service();
        assertThat(service.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline()).getFriendsList()).isEmpty();
        assertThat(service.getPendingRequests(SESSION, GetPendingRequestsRequest.getDefaultInstance(), deadline()).getRequestsList()).isEmpty();
        store.fail = true;
        assertThat(service.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.getPendingRequests(SESSION, GetPendingRequestsRequest.getDefaultInstance(), deadline()).getErrorMessage().getId()).isEqualTo(1003);
    }

    // ================================================================ 工具

    private static TipInfoMessage tip(int id, String message) {
        return TipInfoMessage.newBuilder().setId(id).addParameters(message).build();
    }

    private static AddFriendRequest add(long target) {
        return AddFriendRequest.newBuilder().setTargetPlayerId(target).build();
    }

    private static AcceptFriendRequest accept(long from) {
        return AcceptFriendRequest.newBuilder().setFromPlayerId(from).setRequestId(123).build();
    }

    private static RejectFriendRequest reject(long from) {
        return RejectFriendRequest.newBuilder().setFromPlayerId(from).setRequestId(123).build();
    }

    private static RemoveFriendRequest remove(long target) {
        return RemoveFriendRequest.newBuilder().setTargetPlayerId(target).build();
    }

    @Test
    void 原会话存储拒绝时所有入口回1003_没有关系缓存推送或在线读取副作用() {
        FriendService service = service();
        store.rejectActor = true;
        assertThat(service.addFriend(SESSION, add(200), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.acceptFriend(SESSION, accept(200), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.rejectFriend(SESSION, reject(200), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.removeFriend(SESSION, remove(200), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.block(SESSION, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.unblock(SESSION, UnblockRequest.newBuilder().setTargetPlayerId(200).build(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.getFriendList(SESSION, GetFriendListRequest.getDefaultInstance(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.getPendingRequests(SESSION, GetPendingRequestsRequest.getDefaultInstance(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(service.listBlocks(SESSION, ListBlocksRequest.getDefaultInstance(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(store.calls).isEmpty();
        assertThat(events).isEmpty();
        assertThat(pushed).isEmpty();
        assertThat(onlineCalls).isZero();
    }

    /** 可配置结果、记录调用的存储桩。 */
    static final class StubStore implements FriendStore {
        @Override
        public FriendStore forActor(long playerId, String originalAccount, long originalRevision) {
            assertThat(playerId).isEqualTo(ME);
            assertThat(originalAccount).isEqualTo("original");
            assertThat(originalRevision).isEqualTo(7);
            if (rejectActor) throw new SecurityException("会话已失效");
            return this; // 纯业务测试显式替身；账号与事务校验另由真库用例证明。
        }
        final List<String> calls = new ArrayList<>();
        boolean fail;
        boolean rejectActor;
        boolean created = true;
        boolean changed = true;
        AddResult add = AddResult.OK;
        AcceptResult accept = AcceptResult.OK;
        RejectResult reject = RejectResult.OK;
        RemoveResult remove = RemoveResult.REMOVED;
        BlockResult block = BlockResult.OK;
        List<FriendEdge> friends = List.of();
        List<PendingRequest> pending = List.of();
        List<BlockEdge> blocks = List.of();

        private void call(String what) {
            calls.add(what);
            if (fail) {
                throw new FriendStoreException("mysql down");
            }
        }

        @Override
        public AddOutcome addRequest(long from, long to, Deadline deadline) {
            call("add:" + from + "->" + to);
            return new AddOutcome(add, add == AddResult.OK ? 123 : 0, created);
        }

        @Override
        public AcceptOutcome accept(long requestId, long me, long from, Deadline deadline) {
            call("accept:" + requestId + "->" + me);
            return new AcceptOutcome(accept, 200, changed);
        }

        @Override
        public RejectOutcome reject(long requestId, long me, long from, Deadline deadline) {
            call("reject:" + requestId + "->" + me);
            return new RejectOutcome(reject, 200, changed);
        }

        @Override
        public RemoveResult remove(long me, long target, Deadline deadline) {
            call("remove:" + me + "-" + target);
            return remove;
        }

        @Override
        public BlockResult block(long me, long target, Deadline deadline) {
            call("block:" + me + "-" + target);
            return block;
        }

        @Override
        public void unblock(long me, long target, Deadline deadline) {
            call("unblock:" + me + "-" + target);
        }

        @Override
        public List<FriendEdge> friends(long me, int limit, Deadline deadline) {
            call("friends:" + me);
            return friends;
        }

        @Override
        public List<PendingRequest> pendingRequests(long me, int limit, Deadline deadline) {
            call("pending:" + me);
            return pending;
        }

        @Override
        public List<BlockEdge> blocks(long me, int limit, Deadline deadline) {
            call("blocks:" + me);
            return blocks;
        }
    }
}
