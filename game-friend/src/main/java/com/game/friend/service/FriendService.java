package com.game.friend.service;

import com.game.api.proto.SessionContext;

import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.PlayerPresence;
import com.game.friend.cache.FriendCache;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.PushOutcome;
import com.game.friend.metrics.FriendMetrics.PushReason;
import com.game.common.player.PlayerProfiles;
import com.game.common.player.PlayerProfiles.Profile;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.store.FriendStore;
import com.game.friend.store.FriendStore.AcceptResult;
import com.game.friend.store.FriendStore.AcceptOutcome;
import com.game.friend.store.FriendStore.AddOutcome;
import com.game.friend.store.FriendStore.RejectOutcome;
import com.game.friend.store.FriendStore.BlockEdge;
import com.game.friend.store.FriendStore.BlockResult;
import com.game.friend.store.FriendStore.FriendEdge;
import com.game.friend.store.FriendStore.PendingRequest;
import com.game.friend.store.FriendStore.RejectResult;
import com.game.friend.store.FriendStore.RemoveResult;
import com.game.common.deadline.Deadline;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.AcceptFriendRequest;
import com.game.proto.friend.AcceptFriendResponse;
import com.game.proto.friend.AddFriendRequest;
import com.game.proto.friend.AddFriendResponse;
import com.game.proto.friend.BlockEntry;
import com.game.proto.friend.BlockRequest;
import com.game.proto.friend.BlockResponse;
import com.game.proto.friend.FriendEntry;
import com.game.proto.friend.FriendEventReason;
import com.game.proto.friend.FriendEventS2C;
import com.game.proto.friend.FriendRequest;
import com.game.proto.friend.GetFriendListRequest;
import com.game.proto.friend.GetFriendListResponse;
import com.game.proto.friend.GetPendingRequestsRequest;
import com.game.proto.friend.GetPendingRequestsResponse;
import com.game.proto.friend.ListBlocksRequest;
import com.game.proto.friend.ListBlocksResponse;
import com.game.proto.friend.RejectFriendRequest;
import com.game.proto.friend.RejectFriendResponse;
import com.game.proto.friend.RemoveFriendRequest;
import com.game.proto.friend.RemoveFriendResponse;
import com.game.proto.friend.UnblockRequest;
import com.game.proto.friend.UnblockResponse;
import com.game.table.CommonErrorTip;
import com.game.table.FriendErrorTip;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 好友业务（mmorpg go/friend internal/logic friend_logic.go 的 Java 版，规格 docs/porting/friend-spec.md §2）。
 *
 * <p>全局规则（spec §0.5）：
 * <ul>
 *   <li>业务结果一律走应答体 {@code error_message}（in-band）；成功应答不设置它。</li>
 *   <li>依赖故障（MySQL / Redis / 在线目录）一律回 in-band 1003 {@code "storage unavailable"} 并打 ERROR；原始错误只进日志。</li>
 *   <li>「我」与原账号修订只从完整 {@code SessionContext} 取，传入不可变 Store 请求视图，在提交事务首锁 player 复核；目标 id 来自请求体，入口挡住 0 与「自己」——
 *       否则会落到存储层的非法玩家对、变成 1003 假告警。</li>
 *   <li>提交之后的失败（缓存失效、推送）不改变结果；顺序是「提交 → 失效完成 → 发起推送」，对方收到推送立即拉取时读到新值。</li>
 * </ul>
 * 全部方法阻塞，只在工作线程上调用；每个调用都带一个整请求预算 {@link Deadline}。
 */
public final class FriendService {

    private static final Logger log = LoggerFactory.getLogger(FriendService.class);

    static final String STORAGE_UNAVAILABLE = "storage unavailable";
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;

    private final FriendStore store;
    private final FriendCache cache;
    /** 本部署命名空间的键名（写后要失效的缓存数据键由它生成）。 */
    private final RedisKeys redisKeys;
    private final FriendRequestQuota quota;
    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles;
    private final Function<List<Long>, CompletionStage<StrictLookup>> onlineLookup;
    private final BiFunction<Long, MessageContent, CompletionStage<PlayerPushes.Outcome>> pusher;
    private final FriendMetrics metrics;
    private final int listLimit;
    private final Duration pushTimeout;
    private final LongSupplier clockMs;
    private final int notifyMessageId;

    /**
     * @param redisKeys       本部署命名空间的键名
     * @param profiles        批量读展示资料（{@link PlayerProfiles#load}；失败时返回已读到的部分、永不抛）
     * @param onlineLookup    严格在线批量读（{@code PlayerPresenceDirectory.findAllStrictAsync}，按 listLimit 分批）
     * @param pusher          推一条 {@code MessageContent} 给一个玩家（{@code PlayerPushes.pushToPlayer}）
     * @param listLimit       列表读上限（ListReadHardLimit）
     * @param notifyMessageId 推送 {@code NotifyFriendEvent} 的消息号（取自契约注册表）
     */
    public FriendService(FriendStore store, FriendCache cache, RedisKeys redisKeys, FriendRequestQuota quota,
                         BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles,
                         Function<List<Long>, CompletionStage<StrictLookup>> onlineLookup,
                         BiFunction<Long, MessageContent, CompletionStage<PlayerPushes.Outcome>> pusher,
                         FriendMetrics metrics, int listLimit, Duration pushTimeout, LongSupplier clockMs,
                         int notifyMessageId) {
        this.store = store;
        this.cache = cache;
        this.redisKeys = Objects.requireNonNull(redisKeys, "redisKeys");
        this.quota = quota;
        this.profiles = profiles;
        this.onlineLookup = onlineLookup;
        this.pusher = pusher;
        this.metrics = metrics;
        this.listLimit = listLimit;
        this.pushTimeout = pushTimeout;
        this.clockMs = clockMs;
        this.notifyMessageId = notifyMessageId;
    }

    // ================================================================ AddFriend（234）

    public AddFriendResponse addFriend(SessionContext session, AddFriendRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        long target = request.getTargetPlayerId();
        // 顺序严格照基线：目标为 0 → 加自己 → 配额（前两步不消耗配额）
        if (target == 0) {
            return AddFriendResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER, "target_player_id required")).build();
        }
        if (target == me) {
            return AddFriendResponse.newBuilder()
                    .setErrorMessage(tip(FriendErrorTip.friend_error.kFriendCannotAddSelf_VALUE, "cannot add yourself")).build();
        }
        if (!quota.tryAcquire(me, deadline)) {
            return AddFriendResponse.newBuilder().setErrorMessage(
                    tip(CommonErrorTip.common_error.kRateLimitExceeded_VALUE, "too many friend requests, retry later")).build();
        }
        AddOutcome outcome;
        try {
            outcome = storeFor(session).addRequest(me, target, deadline);
        } catch (RuntimeException e) {
            return AddFriendResponse.newBuilder().setErrorMessage(fault("AddFriend", e)).build();
        }
        TipInfoMessage rejected = switch (outcome.result()) {
            case OK -> null;
            case BLOCKED -> tip(FriendErrorTip.friend_error.kFriendBlocked_VALUE, "cannot send friend request");
            case ALREADY_FRIENDS -> tip(FriendErrorTip.friend_error.kFriendAlreadyFriends_VALUE, "already friends");
            case TOO_MANY_PENDING -> tip(FriendErrorTip.friend_error.kFriendTooManyPending_VALUE, "too many pending friend requests");
            case TARGET_INBOX_FULL -> tip(FriendErrorTip.friend_error.kFriendTargetInboxFull_VALUE, "target inbox full");
            // 申请人（我）满 → 15002，接收方满 → 15004；与 AcceptFriend 的映射相反（spec §0.3）
            case SENDER_FULL -> tip(FriendErrorTip.friend_error.kFriendListFull_VALUE, "your friend list full");
            case RECEIVER_FULL -> tip(FriendErrorTip.friend_error.kFriendTargetListFull_VALUE, "target's friend list full");
        };
        if (rejected != null) {
            return AddFriendResponse.newBuilder().setErrorMessage(rejected).build();
        }
        if (!outcome.created()) {
            return AddFriendResponse.newBuilder().setRequestId(outcome.requestId()).build();
        }
        // 申请行落在接收者的收件箱里，只失效 req(target)
        cache.invalidateAfterCommit(List.of(redisKeys.friendPending(target)), deadline);
        push(target, FriendEventReason.FRIEND_EVENT_REASON_REQUEST_RECEIVED, me);
        log.info("[friend] AddFriend ok from={} to={}", Long.toUnsignedString(me), Long.toUnsignedString(target));
        return AddFriendResponse.newBuilder().setRequestId(outcome.requestId()).build();
    }

    // ================================================================ AcceptFriend（238）

    public AcceptFriendResponse acceptFriend(SessionContext session, AcceptFriendRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        long requestId = request.getRequestId();
        if (requestId == 0) {
            return AcceptFriendResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER, "request_id required")).build();
        }
        AcceptOutcome outcome;
        try {
            // 接受者永远是会话里的 me；旧 from 字段忽略，原申请人由 requestId 对应的权威行裁决。
            outcome = storeFor(session).accept(requestId, me, 0, deadline);
        } catch (RuntimeException e) {
            return AcceptFriendResponse.newBuilder().setErrorMessage(fault("AcceptFriend", e)).build();
        }
        TipInfoMessage rejected = switch (outcome.result()) {
            case OK -> null;
            case NO_PENDING -> tip(FriendErrorTip.friend_error.kFriendNoPendingRequest_VALUE, "no pending friend request");
            case BLOCKED -> tip(FriendErrorTip.friend_error.kFriendBlocked_VALUE, "cannot accept friend request");
            // 原申请人满 → 15004，我（接受者）满 → 15002
            case SENDER_FULL -> tip(FriendErrorTip.friend_error.kFriendTargetListFull_VALUE, "sender's friend list full");
            case ACCEPTOR_FULL -> tip(FriendErrorTip.friend_error.kFriendListFull_VALUE, "your friend list full");
        };
        if (rejected != null) {
            return AcceptFriendResponse.newBuilder().setErrorMessage(rejected).build();
        }
        if (!outcome.changed()) {
            return AcceptFriendResponse.getDefaultInstance();
        }
        long from = outcome.fromPlayerId();
        // req(from) 不能漏：反向 pending 落在 from 的收件箱里
        cache.invalidateAfterCommit(List.of(redisKeys.friendList(from), redisKeys.friendList(me),
                redisKeys.friendPending(me), redisKeys.friendPending(from)), deadline);
        push(from, FriendEventReason.FRIEND_EVENT_REASON_REQUEST_ACCEPTED, me);
        log.info("[friend] AcceptFriend ok from={} me={}", Long.toUnsignedString(from), Long.toUnsignedString(me));
        return AcceptFriendResponse.getDefaultInstance();
    }

    // ================================================================ RejectFriend（232）

    public RejectFriendResponse rejectFriend(SessionContext session, RejectFriendRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        long requestId = request.getRequestId();
        if (requestId == 0) {
            return RejectFriendResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER, "request_id required")).build();
        }
        RejectOutcome outcome;
        try {
            outcome = storeFor(session).reject(requestId, me, 0, deadline);
        } catch (RuntimeException e) {
            return RejectFriendResponse.newBuilder().setErrorMessage(fault("RejectFriend", e)).build();
        }
        if (outcome.result() == RejectResult.NO_PENDING) {
            return RejectFriendResponse.newBuilder().setErrorMessage(tip(FriendErrorTip.friend_error.kFriendNoPendingRequest_VALUE,
                    "no pending friend request")).build();
        }
        if (outcome.changed()) {
            cache.invalidateAfterCommit(List.of(redisKeys.friendPending(me)), deadline);
        }
        return RejectFriendResponse.getDefaultInstance();
    }

    public RemoveFriendResponse removeFriend(SessionContext session, RemoveFriendRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        long target = request.getTargetPlayerId();
        if (target == 0) {
            return RemoveFriendResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER, "target_player_id required")).build();
        }
        if (target == me) {
            return RemoveFriendResponse.getDefaultInstance(); // 删自己：成功、不做任何 I/O（同基线）
        }
        RemoveResult result;
        try {
            result = storeFor(session).remove(me, target, deadline);
        } catch (RuntimeException e) {
            return RemoveFriendResponse.newBuilder().setErrorMessage(fault("RemoveFriend", e)).build();
        }
        if (result == RemoveResult.REMOVED) {
            cache.invalidateAfterCommit(List.of(redisKeys.friendList(me), redisKeys.friendList(target)), deadline);
        }
        return RemoveFriendResponse.getDefaultInstance(); // 不是好友也回成功（幂等），不推送
    }

    // ================================================================ Block（7）/ Unblock（236）

    public BlockResponse block(SessionContext session, BlockRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        long target = request.getTargetPlayerId();
        if (target == 0 || target == me) {
            // 不用 15000：那个文案是「不能加自己为好友」
            return BlockResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER, "target_player_id invalid")).build();
        }
        BlockResult result;
        try {
            result = storeFor(session).block(me, target, deadline);
        } catch (RuntimeException e) {
            return BlockResponse.newBuilder().setErrorMessage(fault("Block", e)).build();
        }
        if (result == BlockResult.BLOCK_LIST_FULL) {
            return BlockResponse.newBuilder()
                    .setErrorMessage(tip(FriendErrorTip.friend_error.kFriendBlockListFull_VALUE, "block list full")).build();
        }
        // 幂等命中（已拉黑过）也失效：事务里照样收敛了残留的边与 pending
        cache.invalidateAfterCommit(List.of(redisKeys.friendList(me), redisKeys.friendList(target),
                redisKeys.friendPending(me), redisKeys.friendPending(target)), deadline);
        // 不推送：被拉黑者不该知道自己被拉黑了
        return BlockResponse.getDefaultInstance();
    }

    public UnblockResponse unblock(SessionContext session, UnblockRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        long target = request.getTargetPlayerId();
        if (target == 0 || target == me) {
            return UnblockResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER, "target_player_id invalid")).build();
        }
        try {
            storeFor(session).unblock(me, target, deadline);
        } catch (RuntimeException e) {
            return UnblockResponse.newBuilder().setErrorMessage(fault("Unblock", e)).build();
        }
        // 黑名单不缓存、不改好友边，不失效；不恢复好友关系
        return UnblockResponse.getDefaultInstance();
    }

    // ================================================================ 读

    public GetFriendListResponse getFriendList(SessionContext session, GetFriendListRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        List<FriendEdge> edges;
        StrictLookup online;
        try {
            // 关系是权威结果；失效失败的旧缓存不能被成功返回。展示资料与在线投影另行补全。
            edges = storeFor(session).friends(me, listLimit, deadline);
            online = onlineStatuses(edges, deadline);
        } catch (RuntimeException e) {
            return GetFriendListResponse.newBuilder().setErrorMessage(fault("GetFriendList", e)).build();
        }
        List<FriendEntry.Builder> entries = new ArrayList<>(edges.size());
        for (FriendEdge edge : edges) {
            FriendEntry.Builder entry = FriendEntry.newBuilder()
                    .setFriendPlayerId(edge.friendPlayerId()).setSinceMs(edge.sinceMs());
            PlayerPresence presence = online.online().get(edge.friendPlayerId());
            if (presence != null) {
                // 在线态与「上线时刻」每次现取、绝不进缓存（D2：last_active_ms 用 online_since_ms 填）
                entry.setIsOnline(true).setLastActiveMs(presence.getOnlineSinceMs());
            }
            entries.add(entry);
        }
        // 资料补全失败只打 ERROR、照常返回（同基线）
        Map<Long, Profile> byId = profiles.apply(edges.stream().map(FriendEdge::friendPlayerId).toList(), deadline);
        GetFriendListResponse.Builder response = GetFriendListResponse.newBuilder();
        for (FriendEntry.Builder entry : entries) {
            Profile profile = byId.get(entry.getFriendPlayerId());
            if (profile != null) {
                entry.setName(nullToEmpty(profile.name())).setLevel(profile.level()).setClassId(profile.classId())
                        .setGender(profile.gender()).setAppearanceId(nullToEmpty(profile.appearanceId()))
                        .setZoneId(profile.zoneId());
            }
            response.addFriends(entry);
        }
        return response.build();
    }

    /**
     * 好友的在线状态（严格读）：列表为空时不碰 Redis；任何一个条目读失败 / 损坏都整体失败（回 1003）——客户端靠 is_online 禁邀离线好友，
     * 「在线状态未知」不能当离线（spec §2.7 第 5 步，基线带着部分结果也回错）。
     */
    private StrictLookup onlineStatuses(List<FriendEdge> edges, Deadline deadline) {
        if (edges.isEmpty()) {
            return new StrictLookup(Map.of(), 0, 0);
        }
        List<Long> ids = edges.stream().map(FriendEdge::friendPlayerId).toList();
        StrictLookup lookup;
        try {
            lookup = deadline.await(onlineLookup.apply(ids), "好友在线状态");
        } catch (Deadline.DependencyException e) {
            metrics.onlineLookups(0, 0, (int) ids.stream().filter(id -> id != 0).distinct().count());
            throw e;
        }
        metrics.onlineLookups(lookup.online().size(), lookup.offline(), lookup.errors());
        if (lookup.errors() > 0) {
            throw new Deadline.DependencyException("好友在线状态有 " + lookup.errors() + " 个读失败或条目损坏");
        }
        return lookup;
    }

    public GetPendingRequestsResponse getPendingRequests(SessionContext session, GetPendingRequestsRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        List<PendingRequest> pending;
        try {
            // 直接 SQL；旧缓存不带代次且可能陈旧，不能参与申请处理或权威列表。
            pending = storeFor(session).pendingRequests(me, listLimit, deadline);
        } catch (RuntimeException e) {
            return GetPendingRequestsResponse.newBuilder().setErrorMessage(fault("GetPendingRequests", e)).build();
        }
        GetPendingRequestsResponse.Builder response = GetPendingRequestsResponse.newBuilder();
        for (PendingRequest row : pending) {
            response.addRequests(FriendRequest.newBuilder()
                    .setFromPlayerId(row.fromPlayerId()).setToPlayerId(row.toPlayerId())
                    .setRequestTimeMs(row.requestTimeMs()).setRequestId(row.requestId())
                    .setStatusValue(row.status())); // 恒为 1，数值原样转换（同基线）
        }
        return response.build();
    }

    public ListBlocksResponse listBlocks(SessionContext session, ListBlocksRequest request, Deadline deadline) {
        long me = session.getPlayerId();
        List<BlockEdge> blocks;
        try {
            // 直读、不缓存（低频且写后必须立刻可见）；刻意不带在线状态（否则拉黑任意 id 就能探测对方是否在线）
            blocks = storeFor(session).blocks(me, listLimit, deadline);
        } catch (RuntimeException e) {
            return ListBlocksResponse.newBuilder().setErrorMessage(fault("ListBlocks", e)).build();
        }
        ListBlocksResponse.Builder response = ListBlocksResponse.newBuilder();
        for (BlockEdge row : blocks) {
            response.addBlocks(BlockEntry.newBuilder().setBlockedPlayerId(row.blockedPlayerId()).setSinceMs(row.sinceMs()));
        }
        return response.build();
    }

    private FriendStore storeFor(SessionContext session) {
        return store.forActor(session.getPlayerId(), session.getAccount(), session.getOwnerRevision());
    }

    // ================================================================ 推送

    /**
     * 推送好友事件（at-most-once，fire-and-forget）：只在提交、失效之后调；失败只记日志与指标，不影响应答；不推给操作者本人。
     * 结局：SENT → ok，OFFLINE → offline，没有订阅者 / Redis 故障 / 超时 → error（D3：基线写进 Kafka 即记 ok）。
     */
    void push(long to, FriendEventReason reason, long by) {
        PushReason metricReason = switch (reason) {
            case FRIEND_EVENT_REASON_REQUEST_RECEIVED -> PushReason.REQUEST_RECEIVED;
            case FRIEND_EVENT_REASON_REQUEST_ACCEPTED -> PushReason.REQUEST_ACCEPTED;
            default -> null;
        };
        if (metricReason == null) {
            log.error("[friend] 未知的好友事件 reason={}，不推送", reason);
            return;
        }
        if (to == 0 || to == by) {
            metrics.push(metricReason, PushOutcome.ERROR);
            return;
        }
        FriendEventS2C event = FriendEventS2C.newBuilder().setReason(reason).setByPlayerId(by).setTsMs(clockMs.getAsLong()).build();
        MessageContent content = MessageContent.newBuilder()
                .setMessageId(notifyMessageId).setSerializedMessage(event.toByteString()).build();
        CompletionStage<PlayerPushes.Outcome> sent;
        try {
            sent = pusher.apply(to, content);
        } catch (RuntimeException e) {
            metrics.push(metricReason, PushOutcome.ERROR);
            log.warn("[friend] 推送好友事件失败 to={} reason={}: {}", Long.toUnsignedString(to), reason, e.toString());
            return;
        }
        sent.toCompletableFuture().orTimeout(pushTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((outcome, error) -> {
            if (error != null) {
                metrics.push(metricReason, PushOutcome.ERROR);
                log.warn("[friend] 推送好友事件失败 to={} reason={}: {}", Long.toUnsignedString(to), reason, error.toString());
                return;
            }
            metrics.push(metricReason, switch (outcome) {
                case SENT -> PushOutcome.OK;
                case OFFLINE -> PushOutcome.OFFLINE;
                case GATE_UNREACHABLE -> PushOutcome.ERROR;
            });
        });
    }

    // ================================================================ 工具

    /** 依赖故障：打 ERROR（原始错误只进日志），回 1003。 */
    private static TipInfoMessage fault(String method, RuntimeException e) {
        if (e instanceof SecurityException) {
            log.warn("[friend] {} 原会话账号归属已失效", method);
            return tip(SERVICE_UNAVAILABLE, STORAGE_UNAVAILABLE);
        }
        log.error("[friend] {} 依赖故障: {}", method, e.toString(), e);
        return tip(SERVICE_UNAVAILABLE, STORAGE_UNAVAILABLE);
    }

    static TipInfoMessage tip(int id, String message) {
        return TipInfoMessage.newBuilder().setId(id).addParameters(message).build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
