package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.AcceptFriendRequest;
import com.game.proto.friend.AcceptFriendResponse;
import com.game.proto.friend.AddFriendRequest;
import com.game.proto.friend.AddFriendResponse;
import com.game.proto.friend.BlockRequest;
import com.game.proto.friend.BlockResponse;
import com.game.proto.friend.FriendEntry;
import com.game.proto.friend.FriendEventReason;
import com.game.proto.friend.FriendEventS2C;
import com.game.proto.friend.FriendRequest;
import com.game.proto.friend.FriendRequestStatus;
import com.game.proto.friend.GetFriendListRequest;
import com.game.proto.friend.GetFriendListResponse;
import com.game.proto.friend.GetPendingRequestsRequest;
import com.game.proto.friend.GetPendingRequestsResponse;
import com.game.proto.friend.ListBlocksRequest;
import com.game.proto.friend.ListBlocksResponse;
import com.game.proto.friend.RecommendFriendsRequest;
import com.game.proto.friend.RecommendFriendsResponse;
import com.game.proto.friend.RejectFriendRequest;
import com.game.proto.friend.RejectFriendResponse;
import com.game.proto.friend.RemoveFriendRequest;
import com.game.proto.friend.RemoveFriendResponse;
import com.game.proto.friend.UnblockRequest;
import com.game.proto.friend.UnblockResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.CommonErrorTip;
import com.game.table.FriendErrorTip;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 好友端到端（对应 mmorpg robot friend_smoke_scenario.go，三个机器人经 gate → xm-friend）：
 * <ol>
 *   <li>A 加 B：受理，B 收到推送 235 {REQUEST_RECEIVED, by = A, ts_ms ≠ 0}；</li>
 *   <li>B 的入站申请里有 A（PENDING，权威拉取路径）；A 重发 → 15003；加自己 → 15000、目标为 0 → 1005；</li>
 *   <li>B 同意：A 收到 235 {REQUEST_ACCEPTED, by = B}；双方好友列表互见，A 看到 B 在线、last_active_ms ≠ 0、名字非空；</li>
 *   <li>C 拉黑 A：C 的黑名单有 A；A→C、C→A 的申请都回 15007（同一个中性码）；</li>
 *   <li>Java 增项：C 加 B → B 拒绝 → 再拒 15005，B 的入站申请里不再有 C；</li>
 *   <li>推荐：批次 4.1b 之前回 in-band 1006（功能未开放）；</li>
 *   <li>A 经 gate 上行 235 → 拿不到业务回包，信封 tip = 1003；</li>
 *   <li>清理：A 删 B（之后 A 的列表里没有 B——写后立刻读到新值）、C 解除拉黑 A。</li>
 * </ol>
 * 每次运行用新账号（标签缺省按时间生成）；开头仍做一遍不断言结果码的幂等预清理，让固定标签可重复跑。
 */
public final class FriendScenario {

    private static final String SERVICE = "ClientPlayerFriend";
    private static final String REF = "PARITY「好友」行";
    /** 好友写消息 gate 限频 5/s、读 10/s（按会话）：相邻请求留间隔。 */
    private static final Duration CALL_SPACING = Duration.ofMillis(250);
    private static final int ALREADY_SENT = FriendErrorTip.friend_error.kFriendRequestAlreadySent_VALUE;
    private static final int BLOCKED = FriendErrorTip.friend_error.kFriendBlocked_VALUE;
    private static final int NO_PENDING = FriendErrorTip.friend_error.kFriendNoPendingRequest_VALUE;
    private static final int CANNOT_ADD_SELF = FriendErrorTip.friend_error.kFriendCannotAddSelf_VALUE;
    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;

    private final PlayerFlow flow;
    private final String accountA;
    private final String accountB;
    private final String accountC;
    private final Duration requestTimeout;
    private final int add;
    private final int accept;
    private final int reject;
    private final int remove;
    private final int list;
    private final int pending;
    private final int block;
    private final int unblock;
    private final int listBlocks;
    private final int recommend;
    private final int notify;

    public FriendScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                          Duration requestTimeout) {
        this.flow = flow;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.requestTimeout = requestTimeout;
        this.add = registry.requireId(SERVICE, "AddFriend");
        this.accept = registry.requireId(SERVICE, "AcceptFriend");
        this.reject = registry.requireId(SERVICE, "RejectFriend");
        this.remove = registry.requireId(SERVICE, "RemoveFriend");
        this.list = registry.requireId(SERVICE, "GetFriendList");
        this.pending = registry.requireId(SERVICE, "GetPendingRequests");
        this.block = registry.requireId(SERVICE, "Block");
        this.unblock = registry.requireId(SERVICE, "Unblock");
        this.listBlocks = registry.requireId(SERVICE, "ListBlocks");
        this.recommend = registry.requireId(SERVICE, "RecommendFriends");
        this.notify = registry.requireId(SERVICE, "NotifyFriendEvent");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "fr" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        List<EnteredPlayer> entered = new ArrayList<>();
        try {
            EnteredPlayer a = flow.enter(accountA, new Timings());
            entered.add(a);
            EnteredPlayer b = flow.enter(accountB, new Timings());
            entered.add(b);
            EnteredPlayer c = flow.enter(accountC, new Timings());
            entered.add(c);
            GameConnection ca = a.connection();
            GameConnection cb = b.connection();
            GameConnection cc = c.connection();
            long ida = a.playerId();
            long idb = b.playerId();
            long idc = c.playerId();
            report.note("A=" + Long.toUnsignedString(ida) + " B=" + Long.toUnsignedString(idb) + " C=" + Long.toUnsignedString(idc));
            preclean(ca, cb, cc, ida, idb, idc);

            // 1. A 加 B，B 收到推送
            int mark = cb.inbox().size();
            AddFriendResponse added = call(ca, add, AddFriendRequest.newBuilder().setTargetPlayerId(idb).build(),
                    AddFriendResponse.parser());
            report.check(tipOf(added.getErrorMessage()) == 0, "A 加 B 受理", "tip=" + tipOf(added.getErrorMessage()), REF);
            Optional<FriendEventS2C> received = awaitPush(cb, mark, FriendEventReason.FRIEND_EVENT_REASON_REQUEST_RECEIVED, ida);
            report.check(received.isPresent() && received.get().getTsMs() > 0, "B 收到 235 申请推送（by = A、ts_ms ≠ 0）",
                    received.map(e -> "ts_ms=" + e.getTsMs()).orElse("10 s 内没收到" + cb.describeSince(mark)), REF);

            // 2. 权威拉取 + 重复申请 + 入口校验
            GetPendingRequestsResponse inbox = call(cb, pending, GetPendingRequestsRequest.getDefaultInstance(),
                    GetPendingRequestsResponse.parser());
            Optional<FriendRequest> fromA = inbox.getRequestsList().stream().filter(r -> r.getFromPlayerId() == ida).findFirst();
            report.check(fromA.isPresent() && fromA.get().getStatus() == FriendRequestStatus.FRIEND_REQUEST_PENDING
                            && fromA.get().getToPlayerId() == idb && fromA.get().getRequestTimeMs() > 0,
                    "B 的入站申请里有 A（PENDING）", inbox.getRequestsCount() + " 条", REF);
            expectTip(report, "A 重发申请 → 15003", ALREADY_SENT,
                    call(ca, add, AddFriendRequest.newBuilder().setTargetPlayerId(idb).build(), AddFriendResponse.parser())
                            .getErrorMessage());
            expectTip(report, "加自己 → 15000", CANNOT_ADD_SELF,
                    call(ca, add, AddFriendRequest.newBuilder().setTargetPlayerId(ida).build(), AddFriendResponse.parser())
                            .getErrorMessage());
            expectTip(report, "目标为 0 → 1005", INVALID_PARAMETER,
                    call(ca, add, AddFriendRequest.getDefaultInstance(), AddFriendResponse.parser()).getErrorMessage());

            // 3. B 同意，A 收到推送，双方互见
            mark = ca.inbox().size();
            AcceptFriendResponse accepted = call(cb, accept, AcceptFriendRequest.newBuilder().setFromPlayerId(ida).build(),
                    AcceptFriendResponse.parser());
            report.check(tipOf(accepted.getErrorMessage()) == 0, "B 同意 A", "tip=" + tipOf(accepted.getErrorMessage()), REF);
            Optional<FriendEventS2C> acceptedPush = awaitPush(ca, mark, FriendEventReason.FRIEND_EVENT_REASON_REQUEST_ACCEPTED, idb);
            report.check(acceptedPush.isPresent(), "A 收到 235 同意推送（by = B）",
                    acceptedPush.map(e -> "ts_ms=" + e.getTsMs()).orElse("10 s 内没收到" + ca.describeSince(mark)), REF);
            GetFriendListResponse aList = call(ca, list, GetFriendListRequest.getDefaultInstance(), GetFriendListResponse.parser());
            Optional<FriendEntry> entryB = aList.getFriendsList().stream().filter(f -> f.getFriendPlayerId() == idb).findFirst();
            report.check(entryB.isPresent() && entryB.get().getIsOnline() && entryB.get().getLastActiveMs() > 0
                            && !entryB.get().getName().isEmpty() && entryB.get().getSinceMs() > 0,
                    "A 的好友列表里有 B：在线、last_active_ms ≠ 0、名字非空",
                    entryB.map(e -> "online=" + e.getIsOnline() + " last_active_ms=" + e.getLastActiveMs()
                            + " name=" + e.getName() + " zone=" + e.getZoneId()).orElse("没有 B，tip=" + tipOf(aList.getErrorMessage())),
                    REF);
            GetFriendListResponse bList = call(cb, list, GetFriendListRequest.getDefaultInstance(), GetFriendListResponse.parser());
            report.check(bList.getFriendsList().stream().anyMatch(f -> f.getFriendPlayerId() == ida),
                    "B 的好友列表里有 A（双向落库）", bList.getFriendsCount() + " 条", REF);
            GetPendingRequestsResponse bInbox = call(cb, pending, GetPendingRequestsRequest.getDefaultInstance(),
                    GetPendingRequestsResponse.parser());
            report.check(bInbox.getRequestsList().stream().noneMatch(r -> r.getFromPlayerId() == ida),
                    "同意之后 B 的入站申请里没有 A（缓存已失效）", bInbox.getRequestsCount() + " 条", REF);

            // 4. 黑名单双向拦截
            BlockResponse blocked = call(cc, block, BlockRequest.newBuilder().setTargetPlayerId(ida).build(), BlockResponse.parser());
            report.check(tipOf(blocked.getErrorMessage()) == 0, "C 拉黑 A", "tip=" + tipOf(blocked.getErrorMessage()), REF);
            ListBlocksResponse blocks = call(cc, listBlocks, ListBlocksRequest.getDefaultInstance(), ListBlocksResponse.parser());
            report.check(blocks.getBlocksList().stream().anyMatch(e -> e.getBlockedPlayerId() == ida && e.getSinceMs() > 0),
                    "C 的黑名单里有 A", blocks.getBlocksCount() + " 条", REF);
            expectTip(report, "被拉黑方 A→C 的申请 → 15007", BLOCKED,
                    call(ca, add, AddFriendRequest.newBuilder().setTargetPlayerId(idc).build(), AddFriendResponse.parser())
                            .getErrorMessage());
            expectTip(report, "拉黑方 C→A 的申请 → 15007（两个方向同码）", BLOCKED,
                    call(cc, add, AddFriendRequest.newBuilder().setTargetPlayerId(ida).build(), AddFriendResponse.parser())
                            .getErrorMessage());

            // 5. 拒绝
            call(cc, add, AddFriendRequest.newBuilder().setTargetPlayerId(idb).build(), AddFriendResponse.parser());
            RejectFriendResponse rejected = call(cb, reject, RejectFriendRequest.newBuilder().setFromPlayerId(idc).build(),
                    RejectFriendResponse.parser());
            report.check(tipOf(rejected.getErrorMessage()) == 0, "B 拒绝 C 的申请", "tip=" + tipOf(rejected.getErrorMessage()), REF);
            expectTip(report, "再拒一次 → 15005", NO_PENDING,
                    call(cb, reject, RejectFriendRequest.newBuilder().setFromPlayerId(idc).build(), RejectFriendResponse.parser())
                            .getErrorMessage());
            GetPendingRequestsResponse afterReject = call(cb, pending, GetPendingRequestsRequest.getDefaultInstance(),
                    GetPendingRequestsResponse.parser());
            report.check(afterReject.getRequestsList().stream().noneMatch(r -> r.getFromPlayerId() == idc),
                    "拒绝之后 B 的入站申请里没有 C", afterReject.getRequestsCount() + " 条", REF);

            // 6. 推荐（4.1b 之前）
            RecommendFriendsResponse rec = call(ca, recommend, RecommendFriendsRequest.newBuilder().setLimit(50).build(),
                    RecommendFriendsResponse.parser());
            expectTip(report, "推荐在批次 4.1b 之前回 1006（功能未开放）", FEATURE_UNAVAILABLE, rec.getErrorMessage());

            // 7. 上行 235
            int before = ca.inbox().size();
            long requestId = ca.send(notify, FriendEventS2C.newBuilder()
                    .setReason(FriendEventReason.FRIEND_EVENT_REASON_REQUEST_ACCEPTED).setByPlayerId(idb).build());
            Optional<Received> envelope = ca.await(before, r -> r.messageId() == notify && r.requestId() == requestId,
                    requestTimeout);
            report.check(envelope.isPresent() && envelope.get().envelopeTipId() == SERVICE_UNAVAILABLE
                            && envelope.get().content().getSerializedMessage().isEmpty(),
                    "客户端经 gate 上行 235 → 信封 1003、没有业务回包",
                    envelope.map(r -> "tip=" + r.envelopeTipId()).orElse("超时" + ca.describeSince(before)), REF);

            // 8. 清理（断言结果码）
            RemoveFriendResponse removed = call(ca, remove, RemoveFriendRequest.newBuilder().setTargetPlayerId(idb).build(),
                    RemoveFriendResponse.parser());
            report.check(tipOf(removed.getErrorMessage()) == 0, "A 删 B", "tip=" + tipOf(removed.getErrorMessage()), REF);
            GetFriendListResponse afterRemove = call(ca, list, GetFriendListRequest.getDefaultInstance(),
                    GetFriendListResponse.parser());
            report.check(afterRemove.getFriendsList().stream().noneMatch(f -> f.getFriendPlayerId() == idb),
                    "删除之后 A 的好友列表里立刻没有 B（写后读）", afterRemove.getFriendsCount() + " 条", REF);
            UnblockResponse unblocked = call(cc, unblock, UnblockRequest.newBuilder().setTargetPlayerId(ida).build(),
                    UnblockResponse.parser());
            report.check(tipOf(unblocked.getErrorMessage()) == 0, "C 解除拉黑 A", "tip=" + tipOf(unblocked.getErrorMessage()), REF);
        } catch (RobotException e) {
            report.fail("流程异常", e.getMessage(), REF);
        } finally {
            for (EnteredPlayer player : entered) {
                player.connection().close();
            }
        }
        return report;
    }

    /** 把三人之间恢复成「互不相识、互不拉黑」：全部幂等，不断言结果码。 */
    private void preclean(GameConnection ca, GameConnection cb, GameConnection cc, long ida, long idb, long idc)
            throws RobotException {
        call(ca, remove, RemoveFriendRequest.newBuilder().setTargetPlayerId(idb).build(), RemoveFriendResponse.parser());
        call(cb, reject, RejectFriendRequest.newBuilder().setFromPlayerId(ida).build(), RejectFriendResponse.parser());
        call(ca, reject, RejectFriendRequest.newBuilder().setFromPlayerId(idb).build(), RejectFriendResponse.parser());
        call(cb, reject, RejectFriendRequest.newBuilder().setFromPlayerId(idc).build(), RejectFriendResponse.parser());
        call(cc, unblock, UnblockRequest.newBuilder().setTargetPlayerId(ida).build(), UnblockResponse.parser());
    }

    private Optional<FriendEventS2C> awaitPush(GameConnection connection, int fromIndex, FriendEventReason reason, long by)
            throws RobotException {
        Optional<Received> push = connection.await(fromIndex, r -> {
            if (r.messageId() != notify || r.requestId() != 0) {
                return false;
            }
            FriendEventS2C event = r.parseOrNull(FriendEventS2C.parser());
            return event != null && event.getReason() == reason && event.getByPlayerId() == by;
        }, Duration.ofSeconds(10));
        return push.map(r -> r.parseOrNull(FriendEventS2C.parser()));
    }

    private <T extends Message> T call(GameConnection connection, int messageId, Message body, Parser<T> parser)
            throws RobotException {
        pause();
        return connection.call(messageId, body, parser, requestTimeout);
    }

    private static void expectTip(CheckReport report, String name, int expected, TipInfoMessage tip) {
        report.check(tipOf(tip) == expected, name, "tip=" + tipOf(tip), REF);
    }

    private static int tipOf(TipInfoMessage tip) {
        return tip == null ? 0 : tip.getId();
    }

    private static void pause() throws RobotException {
        try {
            Thread.sleep(CALL_SPACING.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
