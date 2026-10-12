package com.game.team.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.presence.PlayerPresenceDirectory.PresenceRead;
import com.game.discovery.proto.PlayerPresence;
import com.game.team.rules.SessionState;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 会话四态：presence × location 状态矩阵（team-spec §6.6，§10.3「在线状态映射」）。 */
class TeamSessionsTest {

    private static final long P = Long.MIN_VALUE + 42;

    /** presence 一路的五种情形：在 / 缺 / 损坏或身份不符（ERROR）/ 整路读失败（null）。 */
    private enum PresenceCase { ONLINE, ABSENT, ERROR, READ_FAILED }

    /** location 一路的情形：o / l / x / 缺 / 损坏（ERROR）/ 整路读失败（null）。 */
    private enum LocationCase { ONLINE, LEASE, LOGGED_OUT, MISSING, ERROR, READ_FAILED }

    private static PresenceRead presence(PresenceCase c) {
        return switch (c) {
            case ONLINE -> PresenceRead.online(PlayerPresence.newBuilder().setPlayerId(P).build());
            case ABSENT -> PresenceRead.absent();
            case ERROR -> PresenceRead.error();
            case READ_FAILED -> null;
        };
    }

    private static LocationStatus location(LocationCase c) {
        return switch (c) {
            case ONLINE -> LocationStatus.ONLINE;
            case LEASE -> LocationStatus.RECONNECT_LEASE;
            case LOGGED_OUT -> LocationStatus.LOGGED_OUT;
            case MISSING -> LocationStatus.MISSING;
            case ERROR -> LocationStatus.ERROR;
            case READ_FAILED -> null;
        };
    }

    private static SessionState expected(PresenceCase p, LocationCase l) {
        if (p == PresenceCase.ONLINE) {
            return SessionState.ONLINE;
        }
        if (p != PresenceCase.ABSENT) {
            return SessionState.UNKNOWN;
        }
        return switch (l) {
            case ONLINE, LEASE -> SessionState.PRESENT;
            case LOGGED_OUT, MISSING -> SessionState.ABSENT;
            case ERROR, READ_FAILED -> SessionState.UNKNOWN;
        };
    }

    private static TeamSessions sessions(Function<Collection<Long>, CompletableFuture<Map<Long, PresenceRead>>> presence,
                                         Function<Collection<Long>, CompletableFuture<Map<Long, LocationStatus>>> location) {
        return new TeamSessions(presence, location, id -> CompletableFuture.completedFuture(Optional.empty()));
    }

    @Test
    void 状态矩阵_逐格映射() {
        Map<SessionState, Integer> seen = new EnumMap<>(SessionState.class);
        for (PresenceCase p : PresenceCase.values()) {
            for (LocationCase l : LocationCase.values()) {
                PresenceRead pr = presence(p);
                LocationStatus ls = location(l);
                TeamSessions s = sessions(
                        ids -> pr == null ? CompletableFuture.failedFuture(new IllegalStateException("redis down"))
                                : CompletableFuture.completedFuture(Map.of(P, pr)),
                        ids -> ls == null ? CompletableFuture.failedFuture(new IllegalStateException("redis down"))
                                : CompletableFuture.completedFuture(Map.of(P, ls)));
                SessionState got = s.load(List.of(P), Deadline.after(1000)).get(P);
                assertThat(got).as("presence=%s location=%s", p, l).isEqualTo(expected(p, l));
                assertThat(TeamSessions.stateOf(pr, ls)).isEqualTo(got);
                seen.merge(got, 1, Integer::sum);
            }
        }
        assertThat(seen.keySet()).containsExactlyInAnyOrder(SessionState.values());
    }

    @Test
    void 逐成员fail_closed_一人出错不影响别人() {
        long a = 1, b = 2, c = 3, d = 4;
        Map<Long, PresenceRead> pr = new LinkedHashMap<>();
        pr.put(a, PresenceRead.online(PlayerPresence.newBuilder().setPlayerId(a).build()));
        pr.put(b, PresenceRead.error());
        pr.put(c, PresenceRead.absent());
        pr.put(d, PresenceRead.absent());
        Map<Long, LocationStatus> ls = Map.of(a, LocationStatus.ERROR, b, LocationStatus.ONLINE,
                c, LocationStatus.RECONNECT_LEASE, d, LocationStatus.LOGGED_OUT);
        TeamSessions s = sessions(ids -> CompletableFuture.completedFuture(pr), ids -> CompletableFuture.completedFuture(ls));
        assertThat(s.load(List.of(a, b, c, d, a), Deadline.after(1000))).containsExactly(
                Map.entry(a, SessionState.ONLINE), Map.entry(b, SessionState.UNKNOWN),
                Map.entry(c, SessionState.PRESENT), Map.entry(d, SessionState.ABSENT));
    }

    @Test
    void 读超时或同步抛出都按未知处理_从不抛出() {
        TeamSessions hang = sessions(ids -> new CompletableFuture<>(), ids -> new CompletableFuture<>());
        assertThat(hang.load(List.of(P), Deadline.after(30))).containsExactly(Map.entry(P, SessionState.UNKNOWN));
        TeamSessions throwing = sessions(ids -> {
            throw new IllegalStateException("boom");
        }, ids -> {
            throw new IllegalStateException("boom");
        });
        assertThat(throwing.load(List.of(P), Deadline.after(1000))).containsExactly(Map.entry(P, SessionState.UNKNOWN));
        // presence 在线时 location 读不到也是在线
        TeamSessions locationHang = sessions(
                ids -> CompletableFuture.completedFuture(Map.of(P, PresenceRead.online(PlayerPresence.newBuilder().setPlayerId(P).build()))),
                ids -> new CompletableFuture<>());
        assertThat(locationHang.load(List.of(P), Deadline.after(30))).containsExactly(Map.entry(P, SessionState.ONLINE));
        assertThat(hang.load(List.of(), Deadline.after(30))).isEmpty();
    }

    @Test
    void 邀请目标严格单查_在线_不在线_故障() {
        PlayerPresence p = PlayerPresence.newBuilder().setPlayerId(P).build();
        TeamSessions online = new TeamSessions(null, null, id -> CompletableFuture.completedFuture(Optional.of(p)));
        assertThat(online.isOnline(P, Deadline.after(1000))).isTrue();
        TeamSessions offline = new TeamSessions(null, null, id -> CompletableFuture.completedFuture(Optional.empty()));
        assertThat(offline.isOnline(P, Deadline.after(1000))).isFalse();
        TeamSessions corrupt = new TeamSessions(null, null,
                id -> CompletableFuture.failedFuture(new IllegalStateException("在线目录条目损坏")));
        assertThatThrownBy(() -> corrupt.isOnline(P, Deadline.after(1000))).isInstanceOf(DependencyException.class);
        TeamSessions hang = new TeamSessions(null, null, id -> new CompletableFuture<>());
        assertThatThrownBy(() -> hang.isOnline(P, Deadline.after(20))).isInstanceOf(DependencyException.class);
        TeamSessions throwing = new TeamSessions(null, null, id -> {
            throw new IllegalStateException("closed");
        });
        assertThatThrownBy(() -> throwing.isOnline(P, Deadline.after(1000))).isInstanceOf(DependencyException.class);
    }
}
