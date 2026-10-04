package com.game.friend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.PlayerPresence;
import com.game.friend.cache.FriendCache;
import com.game.friend.cache.InMemoryCacheRedis;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.profile.PlayerProfiles.Profile;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.store.FriendStore;
import com.game.friend.store.FriendStoreException;
import com.game.friend.support.Deadline;
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

    private static final long ME = 100;
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
        FriendCache cache = new FriendCache(new RecordingRedis(), Duration.ofMinutes(30), metrics);
        FriendRequestQuota quota = new FriendRequestQuota(
                key -> CompletableFuture.completedFuture((long) quotaCount.incrementAndGet()), quotaLimit, metrics);
        return new FriendService(store, cache, quota, (ids, deadline) -> {
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
        assertThat(s.addFriend(ME, AddFriendRequest.newBuilder().build(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "target_player_id required"));
        assertThat(s.addFriend(ME, AddFriendRequest.newBuilder().setTargetPlayerId(ME).build(), deadline())
                .getErrorMessage()).isEqualTo(tip(15000, "cannot add yourself"));
        assertThat(quotaCount).hasValue(0);
        assertThat(store.calls).isEmpty();
    }

    @Test
    void 发申请_超配额1008_不碰存储_被拒的尝试也计数() {
        quotaLimit = 1;
        FriendService s = service();
        store.add = FriendStore.AddResult.ALREADY_SENT;
        assertThat(s.addFriend(ME, add(200), deadline()).getErrorMessage().getId()).isEqualTo(15003);
        assertThat(s.addFriend(ME, add(200), deadline()).getErrorMessage())
                .isEqualTo(tip(1008, "too many friend requests, retry later"));
        assertThat(store.calls).hasSize(1);
    }

    @Test
    void 发申请成功_只失效对方收件箱_失效之后才推送_推送内容() throws Exception {
        FriendService s = service();
        assertThat(s.addFriend(ME, add(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).containsExactly("invalidate:" + RedisKeys.friendPending(200), "push:200");
        MessageContent content = pushed.get(0);
        assertThat(content.getMessageId()).isEqualTo(NOTIFY_ID);
        assertThat(content.getId()).isZero();
        FriendEventS2C event = FriendEventS2C.parseFrom(content.getSerializedMessage());
        assertThat(event.getReason()).isEqualTo(FriendEventReason.FRIEND_EVENT_REASON_REQUEST_RECEIVED);
        assertThat(event.getByPlayerId()).isEqualTo(ME);
        assertThat(event.getTsMs()).isEqualTo(123_456L);
        assertThat(registry.get("xm.friend.pushes").tag("reason", "request_received").tag("outcome", "ok").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 发申请_拒绝码映射_满的两个角色与同意相反() {
        FriendService s = service();
        Map<FriendStore.AddResult, Integer> expected = Map.of(
                FriendStore.AddResult.BLOCKED, 15007, FriendStore.AddResult.ALREADY_FRIENDS, 15001,
                FriendStore.AddResult.ALREADY_SENT, 15003, FriendStore.AddResult.TOO_MANY_PENDING, 15006,
                FriendStore.AddResult.TARGET_INBOX_FULL, 15009, FriendStore.AddResult.SENDER_FULL, 15002,
                FriendStore.AddResult.RECEIVER_FULL, 15004);
        expected.forEach((result, code) -> {
            store.add = result;
            assertThat(s.addFriend(ME, add(200), deadline()).getErrorMessage().getId()).as(result.name()).isEqualTo(code);
        });
        assertThat(events).isEmpty(); // 被拒不失效、不推送
    }

    @Test
    void 存储故障是带固定文案的1003() {
        FriendService s = service();
        store.fail = true;
        assertThat(s.addFriend(ME, add(200), deadline()).getErrorMessage()).isEqualTo(tip(1003, "storage unavailable"));
        assertThat(s.acceptFriend(ME, accept(200), deadline()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(s.block(ME, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline())
                .getErrorMessage().getId()).isEqualTo(1003);
        assertThat(s.getFriendList(ME, GetFriendListRequest.getDefaultInstance(), deadline()).getErrorMessage().getId())
                .isEqualTo(1003);
        assertThat(events).isEmpty();
    }

    // ================================================================ AcceptFriend / Reject / Remove

    @Test
    void 同意_入口校验_拒绝码映射_成功失效四个键再推给原申请人() throws Exception {
        FriendService s = service();
        assertThat(s.acceptFriend(ME, AcceptFriendRequest.getDefaultInstance(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "from_player_id required"));
        assertThat(s.acceptFriend(ME, accept(ME), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "cannot accept your own request"));
        Map<FriendStore.AcceptResult, Integer> expected = Map.of(FriendStore.AcceptResult.NO_PENDING, 15005,
                FriendStore.AcceptResult.BLOCKED, 15007, FriendStore.AcceptResult.SENDER_FULL, 15004,
                FriendStore.AcceptResult.ACCEPTOR_FULL, 15002);
        expected.forEach((result, code) -> {
            store.accept = result;
            assertThat(s.acceptFriend(ME, accept(200), deadline()).getErrorMessage().getId()).as(result.name()).isEqualTo(code);
        });
        store.accept = FriendStore.AcceptResult.OK;
        assertThat(s.acceptFriend(ME, accept(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(store.calls).last().isEqualTo("accept:200->100");
        assertThat(events).containsExactly("invalidate:" + RedisKeys.friendList(200), "invalidate:" + RedisKeys.friendList(ME),
                "invalidate:" + RedisKeys.friendPending(ME), "invalidate:" + RedisKeys.friendPending(200), "push:200");
        assertThat(FriendEventS2C.parseFrom(pushed.get(0).getSerializedMessage()).getReason())
                .isEqualTo(FriendEventReason.FRIEND_EVENT_REASON_REQUEST_ACCEPTED);
    }

    @Test
    void 拒绝_无申请15005不失效_成功只失效自己收件箱不推送() {
        FriendService s = service();
        assertThat(s.rejectFriend(ME, reject(ME), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "cannot reject your own request"));
        store.reject = FriendStore.RejectResult.NO_PENDING;
        assertThat(s.rejectFriend(ME, reject(200), deadline()).getErrorMessage())
                .isEqualTo(tip(15005, "no pending friend request"));
        assertThat(events).isEmpty();
        store.reject = FriendStore.RejectResult.OK;
        assertThat(s.rejectFriend(ME, reject(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).containsExactly("invalidate:" + RedisKeys.friendPending(ME));
    }

    @Test
    void 删除_删自己不做IO_不是好友成功且不失效_删到才失效双方列表() {
        FriendService s = service();
        assertThat(s.removeFriend(ME, RemoveFriendRequest.getDefaultInstance(), deadline()).getErrorMessage().getId())
                .isEqualTo(1005);
        assertThat(s.removeFriend(ME, remove(ME), deadline()).hasErrorMessage()).isFalse();
        assertThat(store.calls).isEmpty();
        store.remove = FriendStore.RemoveResult.NOT_FRIENDS;
        assertThat(s.removeFriend(ME, remove(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).isEmpty();
        store.remove = FriendStore.RemoveResult.REMOVED;
        assertThat(s.removeFriend(ME, remove(200), deadline()).hasErrorMessage()).isFalse();
        assertThat(events).containsExactly("invalidate:" + RedisKeys.friendList(ME), "invalidate:" + RedisKeys.friendList(200));
    }

    // ================================================================ Block / Unblock

    @Test
    void 拉黑_目标非法1005_满15008_成功失效四个键不推送_解除不失效() {
        FriendService s = service();
        assertThat(s.block(ME, BlockRequest.newBuilder().setTargetPlayerId(ME).build(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "target_player_id invalid"));
        store.block = FriendStore.BlockResult.BLOCK_LIST_FULL;
        assertThat(s.block(ME, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline()).getErrorMessage())
                .isEqualTo(tip(15008, "block list full"));
        store.block = FriendStore.BlockResult.OK;
        assertThat(s.block(ME, BlockRequest.newBuilder().setTargetPlayerId(200).build(), deadline()).hasErrorMessage())
                .isFalse();
        assertThat(events).containsExactly("invalidate:" + RedisKeys.friendList(ME), "invalidate:" + RedisKeys.friendList(200),
                "invalidate:" + RedisKeys.friendPending(ME), "invalidate:" + RedisKeys.friendPending(200));
        events.clear();
        assertThat(s.unblock(ME, UnblockRequest.newBuilder().setTargetPlayerId(0).build(), deadline()).getErrorMessage())
                .isEqualTo(tip(1005, "target_player_id invalid"));
        assertThat(s.unblock(ME, UnblockRequest.newBuilder().setTargetPlayerId(200).build(), deadline()).hasErrorMessage())
                .isFalse();
        assertThat(events).isEmpty();
    }

    // ================================================================ 读

    @Test
    void 好友列表_在线态现取_资料补全_缓存命中后不再回源() {
        store.friends = List.of(new FriendStore.FriendEdge(200, 10), new FriendStore.FriendEdge(300, 20));
        online.put(200L, PlayerPresence.newBuilder().setPlayerId(200).setOnlineSinceMs(999).build());
        profiles.put(200L, new Profile(200, "alice", 12, 3, 1, "ap", 7));
        FriendService s = service();
        List<FriendEntry> friends = s.getFriendList(ME, GetFriendListRequest.getDefaultInstance(), deadline()).getFriendsList();
        assertThat(friends).hasSize(2);
        assertThat(friends.get(0)).isEqualTo(FriendEntry.newBuilder().setFriendPlayerId(200).setSinceMs(10).setIsOnline(true)
                .setLastActiveMs(999).setName("alice").setLevel(12).setClassId(3).setGender(1).setAppearanceId("ap")
                .setZoneId(7).build());
        assertThat(friends.get(1)).isEqualTo(FriendEntry.newBuilder().setFriendPlayerId(300).setSinceMs(20).build());
        s.getFriendList(ME, GetFriendListRequest.getDefaultInstance(), deadline());
        assertThat(store.calls).containsExactly("friends:100");
        assertThat(registry.get("xm.friend.online.lookups").tag("outcome", "ok").counter().count()).isEqualTo(2);
        assertThat(registry.get("xm.friend.online.lookups").tag("outcome", "offline").counter().count()).isEqualTo(2);
    }

    @Test
    void 好友列表_在线状态有一个读失败就整体1003_空列表不读在线() {
        FriendService s = service();
        store.friends = List.of();
        assertThat(s.getFriendList(ME, GetFriendListRequest.getDefaultInstance(), deadline()).getFriendsList()).isEmpty();
        assertThat(onlineCalls).isZero();

        store.friends = List.of(new FriendStore.FriendEdge(200, 10), new FriendStore.FriendEdge(300, 20));
        cacheRedis.values.clear();
        online.put(200L, PlayerPresence.newBuilder().setPlayerId(200).build());
        onlineErrors = 1;
        var response = s.getFriendList(ME, GetFriendListRequest.getDefaultInstance(), deadline());
        assertThat(response.getErrorMessage()).isEqualTo(tip(1003, "storage unavailable"));
        assertThat(response.getFriendsList()).isEmpty();
    }

    @Test
    void 入站申请与黑名单列表() {
        store.pending = List.of(new FriendStore.PendingRequest(200, ME, 55, 1));
        store.blocks = List.of(new FriendStore.BlockEdge(300, 66));
        FriendService s = service();
        var pending = s.getPendingRequests(ME, GetPendingRequestsRequest.getDefaultInstance(), deadline()).getRequests(0);
        assertThat(pending.getFromPlayerId()).isEqualTo(200);
        assertThat(pending.getToPlayerId()).isEqualTo(ME);
        assertThat(pending.getRequestTimeMs()).isEqualTo(55);
        assertThat(pending.getStatus()).isEqualTo(FriendRequestStatus.FRIEND_REQUEST_PENDING);
        var blocks = s.listBlocks(ME, ListBlocksRequest.getDefaultInstance(), deadline()).getBlocksList();
        assertThat(blocks).singleElement().satisfies(b -> {
            assertThat(b.getBlockedPlayerId()).isEqualTo(300);
            assertThat(b.getSinceMs()).isEqualTo(66);
        });
        assertThat(onlineCalls).isZero(); // 黑名单刻意不带在线状态
    }

    // ================================================================ 工具

    private static TipInfoMessage tip(int id, String message) {
        return TipInfoMessage.newBuilder().setId(id).addParameters(message).build();
    }

    private static AddFriendRequest add(long target) {
        return AddFriendRequest.newBuilder().setTargetPlayerId(target).build();
    }

    private static AcceptFriendRequest accept(long from) {
        return AcceptFriendRequest.newBuilder().setFromPlayerId(from).build();
    }

    private static RejectFriendRequest reject(long from) {
        return RejectFriendRequest.newBuilder().setFromPlayerId(from).build();
    }

    private static RemoveFriendRequest remove(long target) {
        return RemoveFriendRequest.newBuilder().setTargetPlayerId(target).build();
    }

    /** 可配置结果、记录调用的存储桩。 */
    static final class StubStore implements FriendStore {
        final List<String> calls = new ArrayList<>();
        boolean fail;
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
        public AddResult addRequest(long from, long to, Deadline deadline) {
            call("add:" + from + "->" + to);
            return add;
        }

        @Override
        public AcceptResult accept(long from, long me, Deadline deadline) {
            call("accept:" + from + "->" + me);
            return accept;
        }

        @Override
        public RejectResult reject(long from, long me, Deadline deadline) {
            call("reject:" + from + "->" + me);
            return reject;
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
