package com.game.team.presence;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.presence.PlayerPresenceDirectory.PresenceRead;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.rules.SessionState;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话四态（替代基线 {@code player:session}，team-spec §6.6，D3）：
 *
 * <table>
 *   <caption>presence × location → 四态</caption>
 *   <tr><th>{@code xm:presence:{pid}}</th><th>{@code xm:location:{pid}} 的 {@code s}</th><th>结果</th></tr>
 *   <tr><td>存在、能解码、玩家号一致</td><td>（不看）</td><td>{@link SessionState#ONLINE}</td></tr>
 *   <tr><td>不存在</td><td>{@code o}（scene 认为在线，presence 还没写或已丢）/ {@code l}（30 s 重连租约）</td><td>{@link SessionState#PRESENT}</td></tr>
 *   <tr><td>不存在</td><td>不存在 / {@code x}（登出墓碑）</td><td>{@link SessionState#ABSENT}</td></tr>
 *   <tr><td>读失败、损坏、身份不符</td><td>（不看）</td><td>{@link SessionState#UNKNOWN}</td></tr>
 *   <tr><td>不存在</td><td>读失败、状态值不认识</td><td>{@link SessionState#UNKNOWN}</td></tr>
 * </table>
 *
 * 直接把「presence 缺失」当 ABSENT 会在 30 s 重连宽限内错误地惰性转让队长（基线把这段宽限当转让宽限，§8.2 第 4 条）。
 * 与好友不同：组队逐成员 fail-closed，一个人读失败只让这个人是 UNKNOWN，从不让 RPC 失败（基线 presence.go:56-82）。
 *
 * <p>两类键没有共同的 hash tag，所以分两路异步读（presence 一次 MGET、location 每人一条 HGET）并行发出，不合成一段 Lua。
 * 在调用线程上等待结果（上界是 deadline），只在工作线程上调用。线程安全。
 */
public final class TeamSessions implements SessionReads {

    private static final Logger log = LoggerFactory.getLogger(TeamSessions.class);

    private final Function<Collection<Long>, CompletableFuture<Map<Long, PresenceRead>>> presenceEach;
    private final Function<Collection<Long>, CompletableFuture<Map<Long, LocationStatus>>> locationStatuses;
    private final LongFunction<CompletionStage<Optional<PlayerPresence>>> presenceStrict;

    /**
     * @param presenceEach     严格逐人读在线目录（生产 {@code PlayerPresenceDirectory::findEachStrictAsync}，future 从不异常完成）
     * @param locationStatuses 批量读位置记录状态（生产 {@code PlayerLocationDirectory::statusesAsync}，future 从不异常完成）
     * @param presenceStrict   严格单查在线目录（生产 {@code PlayerPresenceDirectory::findStrictAsync}，损坏 / 与键不符时异常完成）
     */
    public TeamSessions(Function<Collection<Long>, CompletableFuture<Map<Long, PresenceRead>>> presenceEach,
                        Function<Collection<Long>, CompletableFuture<Map<Long, LocationStatus>>> locationStatuses,
                        LongFunction<CompletionStage<Optional<PlayerPresence>>> presenceStrict) {
        this.presenceEach = presenceEach;
        this.locationStatuses = locationStatuses;
        this.presenceStrict = presenceStrict;
    }

    @Override
    public Map<Long, SessionState> load(Collection<Long> members, Deadline deadline) {
        List<Long> ids = members == null ? List.of() : members.stream().distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        CompletableFuture<Map<Long, PresenceRead>> presence = start(presenceEach, ids);
        CompletableFuture<Map<Long, LocationStatus>> locations = start(locationStatuses, ids);
        Map<Long, PresenceRead> presenceReads = awaitOrNull(presence, deadline, "在线目录逐人严格读", ids);
        Map<Long, LocationStatus> locationReads = awaitOrNull(locations, deadline, "位置记录状态批量读", ids);
        Map<Long, SessionState> states = new LinkedHashMap<>();
        for (long id : ids) {
            states.put(id, stateOf(presenceReads == null ? null : presenceReads.get(id),
                    locationReads == null ? null : locationReads.get(id)));
        }
        return Collections.unmodifiableMap(states);
    }

    @Override
    public boolean isOnline(long playerId, Deadline deadline) {
        CompletionStage<Optional<PlayerPresence>> read;
        try {
            read = presenceStrict.apply(playerId);
        } catch (RuntimeException e) {
            throw new DependencyException("读在线目录失败 player=" + Long.toUnsignedString(playerId), e);
        }
        try {
            return deadline.await(read, "读在线目录 player=" + Long.toUnsignedString(playerId)).isPresent();
        } catch (DependencyException e) {
            throw e;
        } catch (RuntimeException e) { // 被取消等
            throw new DependencyException("读在线目录失败 player=" + Long.toUnsignedString(playerId), e);
        }
    }

    /**
     * 一名成员的四态（null 表示这一路没读到：整路超时或失败）。
     */
    static SessionState stateOf(PresenceRead presence, LocationStatus location) {
        if (presence == null) {
            return SessionState.UNKNOWN;
        }
        return switch (presence.status()) {
            case ONLINE -> SessionState.ONLINE;
            case ERROR -> SessionState.UNKNOWN;
            case ABSENT -> {
                if (location == null) {
                    yield SessionState.UNKNOWN;
                }
                yield switch (location) {
                    case ONLINE, RECONNECT_LEASE -> SessionState.PRESENT;
                    case MISSING, LOGGED_OUT -> SessionState.ABSENT;
                    case ERROR -> SessionState.UNKNOWN;
                };
            }
        };
    }

    private static <T> CompletableFuture<T> start(Function<Collection<Long>, CompletableFuture<T>> read, List<Long> ids) {
        try {
            return read.apply(ids);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static <T> T awaitOrNull(CompletableFuture<T> future, Deadline deadline, String what, List<Long> ids) {
        try {
            return deadline.await(future, what);
        } catch (RuntimeException e) { // DependencyException（失败 / 超出预算）或被取消
            log.warn("[team] {} 失败，{} 名成员按会话未知处理（fail-closed）: {}", what, ids.size(), e.toString());
            return null;
        }
    }
}
