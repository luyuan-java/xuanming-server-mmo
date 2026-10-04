package com.game.friend.service;

import com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup;
import com.game.discovery.proto.PlayerPresence;
import com.game.friend.directory.OnlineDirectory;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.profile.PlayerProfiles.Profile;
import com.game.friend.quota.DirectoryQuota;
import com.game.friend.store.RecommendSource;
import com.game.friend.store.RecommendStore.Candidate;
import com.game.friend.support.Deadline;
import com.game.friend.support.Deadline.DependencyException;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.RecommendEntry;
import com.game.proto.friend.RecommendFriendsRequest;
import com.game.proto.friend.RecommendFriendsResponse;
import com.game.table.CommonErrorTip;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RecommendFriends（119）：普通推荐与在线目录（mmorpg go/friend internal/logic/{recommend,online_directory}.go，friend-spec.md §2.10、§5）。
 *
 * <p>推荐是<b>可降级的展示功能</b>：越界的 limit 钳到上限而不是报错；在线态取不到就当离线；候选允许轻微陈旧。唯一 fail-closed 的输入是
 * exclude 条数（客户端可控的数组长度直接决定 SQL 的占位符个数），超了回 1005、不截断（截断会让客户端以为排除了的人又被推回来）。
 *
 * <ul>
 *   <li>普通推荐：排除集 = exclude + 自己；策略链固定两级 mutual（好友的好友，共同好友数降序）→ random（随机锚点兜底），每级只补缺口、
 *       选中的追加进排除集；<b>任一级出错立刻回 1003</b>，不做部分降级；只补在线态（不填名字等展示字段），{@code online_directory = false}。</li>
 *   <li>在线目录（{@code online_only}）：从这一步起所有应答都带 {@code online_directory = true}；先校验游标与 query（非法输入不占额度）、
 *       再计每分钟 60 页的配额（故障 fail-closed），再取调用者的 home zone（缺失或 0 → 1003，不返回跨区玩家），最后列一页。</li>
 * </ul>
 * 服务端不留状态：「换一批」靠客户端回传 exclude，同一请求重放结果不同（同分随机、锚点随机）。阻塞，只在工作线程上调用。
 */
public final class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int RATE_LIMITED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    static final long ONLINE_FAILURE_LOG_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);

    private final RecommendSource store;
    private final OnlineDirectory directory;
    private final DirectoryQuota directoryQuota;
    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> profilesStrict;
    private final Function<List<Long>, CompletionStage<StrictLookup>> onlineLookup;
    private final AtomicLong lastOnlineFailureLog = new AtomicLong();
    private final FriendMetrics metrics;
    private final int defaultLimit;
    private final int maxLimit;
    private final int maxExclude;

    /**
     * @param profilesStrict 批量读 player 行，读失败抛 {@link DependencyException}（取调用者 home zone 用）
     * @param onlineLookup   在线目录的严格批量读；推荐的在线态取不到（含损坏条目）就当离线
     */
    public RecommendService(RecommendSource store, OnlineDirectory directory, DirectoryQuota directoryQuota,
                            BiFunction<List<Long>, Deadline, Map<Long, Profile>> profilesStrict,
                            Function<List<Long>, CompletionStage<StrictLookup>> onlineLookup,
                            FriendMetrics metrics, int defaultLimit, int maxLimit, int maxExclude) {
        this.store = store;
        this.directory = directory;
        this.directoryQuota = directoryQuota;
        this.profilesStrict = profilesStrict;
        this.onlineLookup = onlineLookup;
        this.metrics = metrics;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.maxExclude = maxExclude;
    }

    /** 0 → 缺省；超过上限钳到上限（uint32 按无符号比较）。 */
    int clampLimit(int requested) {
        long limit = Integer.toUnsignedLong(requested);
        if (limit == 0) {
            limit = defaultLimit;
        }
        return (int) Math.min(limit, maxLimit);
    }

    public RecommendFriendsResponse recommendFriends(long me, RecommendFriendsRequest request, Deadline deadline) {
        if (request.getExcludePlayerIdsCount() > maxExclude) {
            return RecommendFriendsResponse.newBuilder()
                    .setErrorMessage(tip(INVALID_PARAMETER, "too many exclude_player_ids")).build();
        }
        int limit = clampLimit(request.getLimit());
        if (request.getOnlineOnly()) {
            return onlineDirectory(me, request, limit, deadline);
        }
        List<Long> exclude = new ArrayList<>(request.getExcludePlayerIdsList());
        exclude.add(me);
        List<Candidate> picked = new ArrayList<>();
        String step = "mutual";
        try {
            for (Candidate c : store.mutual(me, exclude, limit, deadline)) {
                picked.add(c);
                exclude.add(c.playerId());
            }
            if (picked.size() < limit) {
                step = "random";
                for (Candidate c : store.random(me, exclude, limit - picked.size(), deadline)) {
                    picked.add(c);
                    exclude.add(c.playerId());
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.error("[friend] RecommendFriends 策略 {} 查询失败 player={}: {}", step, Long.toUnsignedString(me), e.toString());
            return RecommendFriendsResponse.newBuilder()
                    .setErrorMessage(tip(SERVICE_UNAVAILABLE, "recommend candidates unavailable")).build();
        }
        Map<Long, PlayerPresence> online = onlineStatuses(picked, deadline);
        RecommendFriendsResponse.Builder response = RecommendFriendsResponse.newBuilder();
        for (Candidate c : picked) {
            RecommendEntry.Builder entry = RecommendEntry.newBuilder()
                    .setCandidatePlayerId(c.playerId()).setMutualFriends(c.mutualFriends());
            PlayerPresence presence = online.get(c.playerId());
            if (presence != null) {
                entry.setIsOnline(true).setLastActiveMs(presence.getOnlineSinceMs());
            }
            response.addCandidates(entry);
        }
        return response.build();
    }

    /**
     * 推荐候选的在线态（基线 FillOnlineStatus）：严格读，但任何失败都降级成离线（展示字段，推荐照常可用）；读失败与损坏条目计 error、
     * 至多每 10 s 打一条 ERROR（同基线 session_reader 的日志限频）。
     */
    private Map<Long, PlayerPresence> onlineStatuses(List<Candidate> picked, Deadline deadline) {
        if (picked.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = picked.stream().map(Candidate::playerId).toList();
        try {
            StrictLookup lookup = deadline.await(onlineLookup.apply(ids), "推荐候选在线状态");
            metrics.onlineLookups(lookup.online().size(), lookup.offline(), lookup.errors());
            if (lookup.errors() > 0) {
                logOnlineFailure(lookup.errors() + " 个条目读失败或损坏");
            }
            return lookup.online();
        } catch (DependencyException e) {
            metrics.onlineLookups(0, 0, (int) ids.stream().distinct().count());
            logOnlineFailure(e.toString());
            return Map.of();
        }
    }

    private void logOnlineFailure(String what) {
        long now = System.nanoTime();
        long last = lastOnlineFailureLog.get();
        if ((last == 0 || now - last >= ONLINE_FAILURE_LOG_INTERVAL_NANOS) && lastOnlineFailureLog.compareAndSet(last, now)) {
            log.error("[friend] 推荐候选在线状态读取失败，相关候选按离线返回: {}", what);
        }
    }

    private RecommendFriendsResponse onlineDirectory(long me, RecommendFriendsRequest request, int limit, Deadline deadline) {
        if (!OnlineDirectory.validInput(request.getCursor(), request.getQuery())) {
            return directoryRejected(INVALID_PARAMETER, "invalid online directory cursor or query");
        }
        switch (directoryQuota.tryAcquire(me, deadline)) {
            case ERROR -> {
                log.error("[friend] 在线目录限流读取失败 player={}", Long.toUnsignedString(me));
                return directoryRejected(SERVICE_UNAVAILABLE, "online directory limiter unavailable");
            }
            case REJECTED -> {
                return directoryRejected(RATE_LIMITED, "online directory rate limited");
            }
            case ALLOWED -> {
            }
        }
        try {
            Profile caller = profilesStrict.apply(List.of(me), deadline).get(me);
            if (caller == null || caller.zoneId() == 0) {
                throw new DependencyException("在线目录调用者归属区未知");
            }
            OnlineDirectory.Page page = directory.list(me, caller.zoneId(), request.getCursor(), limit,
                    request.getExcludePlayerIdsList(), request.getQuery(), deadline);
            return RecommendFriendsResponse.newBuilder().setOnlineDirectory(true)
                    .addAllCandidates(page.candidates()).setNextCursor(page.nextCursor()).build();
        } catch (IllegalArgumentException e) {
            return directoryRejected(INVALID_PARAMETER, "invalid online directory parameters");
        } catch (RuntimeException e) {
            log.error("[friend] 在线目录读取失败 player={}: {}", Long.toUnsignedString(me), e.toString());
            return directoryRejected(SERVICE_UNAVAILABLE, "online directory unavailable");
        }
    }

    private static RecommendFriendsResponse directoryRejected(int code, String message) {
        return RecommendFriendsResponse.newBuilder().setOnlineDirectory(true).setErrorMessage(tip(code, message)).build();
    }

    private static TipInfoMessage tip(int id, String message) {
        return TipInfoMessage.newBuilder().setId(id).addParameters(message).build();
    }
}
